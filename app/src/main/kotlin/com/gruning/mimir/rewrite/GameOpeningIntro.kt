package com.gruning.mimir.rewrite

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.media.MediaPlayer
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.gruning.mimir.rewrite.core.audio.AudioPreferences
import com.gruning.mimir.rewrite.core.audio.MimirFrontendMusic
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirArtInkStore
import com.gruning.mimir.rewrite.core.ui.MimirFlatLogoArt
import com.gruning.mimir.rewrite.core.ui.MimirLogoStage
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.rememberMimirLogoInk
import com.gruning.mimir.rewrite.feature.library.LibraryLaunchOpening
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext

data class GameOpeningIntroPlan(
    val gameIdValue: String,
    val title: String,
    val heroUri: String,
    val logoUri: String?,
    val audioUri: String?,
)

internal const val GameOpeningIntroHoldMs = 3_000L
internal const val GameOpeningIntroEnterMs = 600
internal const val GameOpeningIntroLogoOffsetDp = 12
internal const val GameOpeningIntroExitMs = 400

/**
 * Holds the intro for 0.2 seconds after the game gains the screen. Audio fades across the hold,
 * so the music and overlay end together.
 */
internal const val GameOpeningIntroYieldHoldMs = 200L

/**
 * The longest a dispatched launch waits for the game to take the screen: the dispatch watchdog's whole window,
 * [GameDispatchPrimaryGraceMs] to reach a verdict plus [GameDispatchRecoveryDeadlineMs] to reap the target and settle
 * the session. A launch that leaves Mimir resumed is settled by then whatever it does, and the watchdog speaks first
 * (at 3 s, so before this cap) whenever it can at all; it cannot while Mimir is merely paused under a slow emulator,
 * which is exactly the case this cap outlasts. Past it the intro would cover a UI the ledger has already handed back.
 */
internal const val GameOpeningIntroHandoffCapMs = GameDispatchPrimaryGraceMs + GameDispatchRecoveryDeadlineMs

/**
 * The pace of the slow zoom, 1 to 1.05: the original hold, the old fixed 2.5 s grace and the exit added
 * up. The grace is no longer a fixed wait (the intro ends when the game has the screen), but the zoom keeps its pace and
 * simply rests at 1.05 if the wait runs longer.
 */
internal const val GameOpeningIntroZoomMs = 5_900

/** Where the logo's entrance starts (it grows to 1) and where the hero's zoom ends (it starts at 1). */
internal const val GameOpeningIntroLogoScaleFrom = 0.92f
internal const val GameOpeningIntroZoomTo = 1.05f

/**
 * One frame of the intro: what each of its four animated values is. The layer animates them with `Animatable`s it keeps
 * to itself, so the launch cover cannot ask the layer; it asks this.
 */
internal data class GameOpeningIntroFrameValues(
    val heroAlpha: Float,
    val logoAlpha: Float,
    val logoScale: Float,
    val zoom: Float,
)

/**
 * The intro's frame [elapsedMs] after it was shown, from the same tweens the layer runs: the entrance ([GameOpeningIntroEnterMs])
 * fades the hero and the logo in and grows the logo, the zoom ([GameOpeningIntroZoomMs]) creeps to [GameOpeningIntroZoomTo],
 * all on [MimirMotion.Easing]. The layer's animations begin when its first composition is applied, a frame or so after the
 * plan is shown, so this can lead the live values by that much. By the time a launch dispatches (the intro holds 3 s first)
 * only the zoom is still moving, at about 0.01 a second, so a 50 ms lead is 0.0005 of scale: half a pixel at the very edge
 * of a 1920 px screen and nothing at its centre. Device proof, not proven here: the handover frame.
 *
 * It is a function of time and not a read of the layer so the cover can take its snapshot from any thread, before the
 * intent fires, without asking Compose for anything.
 */
internal fun gameOpeningIntroFrameAt(elapsedMs: Long): GameOpeningIntroFrameValues {
    val t = elapsedMs.coerceAtLeast(0L).toFloat()
    val entrance = MimirMotion.Easing.transform((t / GameOpeningIntroEnterMs).coerceIn(0f, 1f))
    val zoom = MimirMotion.Easing.transform((t / GameOpeningIntroZoomMs).coerceIn(0f, 1f))
    return GameOpeningIntroFrameValues(
        heroAlpha = entrance,
        logoAlpha = entrance,
        logoScale = GameOpeningIntroLogoScaleFrom + (1f - GameOpeningIntroLogoScaleFrom) * entrance,
        zoom = 1f + (GameOpeningIntroZoomTo - 1f) * zoom,
    )
}

/**
 * What the launch cover draws, taken ONCE before the intent fires ([GameOpeningIntroState.coverSnapshotFor]) and never
 * read from [GameOpeningIntroState] again. The yield path clears that state with `hideIfCurrentWithoutExit`; a cover that
 * read it live would go blank at the moment it takes over. [hero] is the very bitmap the intro's own `AsyncImage` loaded
 * (from the resident Coil cache), so the cover decodes nothing and cannot differ from the frame under it.
 */
internal data class GameLaunchCoverSnapshot(
    val gameIdValue: String,
    val title: String,
    val heroUri: String,
    val hero: ImageBitmap,
    val logoUri: String?,
    val frame: GameOpeningIntroFrameValues,
)

private const val GameOpeningIntroAudioRampMs = 300L
private const val GameOpeningIntroAudioRampSteps = 20
private const val GameOpeningIntroLogTag = "MimirOpening"
private const val GameOpeningIntroSystemProperty = "debug.mimir.opening"

/**
 * Resolves [game] to the same hero/motion sources the Home shelf actually renders, before handing it
 * to [gameOpeningIntroPlan] / [gameOpeningIntroSkipReason].
 *
 * [GameRow.heroArtUri] (and [GameRow.previewVideoUri]) is only the automatic, DB-managed hero column.
 * The shelf never renders that column alone — it layers the player's explicit Customize Media pick on
 * top via [CustomHeroStore.overrideUri] / [CustomHeroStore.previewClipUri] (see the shelf's
 * `heroArtUriForGame` / `heroMotionUriForGame`). Enriched/custom heroes live only in [CustomHeroStore]
 * (`mimir_custom_hero.xml` + `files/enrich/hero/…`), so [GameRow.heroArtUri] is null for exactly those
 * games. Without this seam the intro reads the bare DB column and logs `reason=no-hero` for a game the
 * shelf is visibly showing a hero for.
 */
internal fun gameOpeningIntroSources(context: Context, game: GameRow): GameRow = game.copy(
    heroArtUri = CustomHeroStore.overrideUri(context, game.gameId) ?: game.heroArtUri,
    previewVideoUri = CustomHeroStore.previewClipUri(context, game.gameId) ?: game.previewVideoUri,
)

internal fun gameOpeningIntroPlan(
    game: GameRow,
    placement: MimirGamePlacement,
    enabled: Boolean,
): GameOpeningIntroPlan? {
    if (gameOpeningIntroSkipReason(game, placement, enabled) != null) return null
    val heroUri = game.heroArtUri?.takeIf { it.isNotBlank() } ?: return null
    return GameOpeningIntroPlan(
        gameIdValue = game.gameId.value,
        title = game.title,
        heroUri = heroUri,
        logoUri = game.flatArtUri?.takeIf { it.isNotBlank() },
        audioUri = gameOpeningIntroAudioUri(game.previewVideoUri, game.launchAudioUri),
    )
}

/**
 * The sound under the launch animation : the player's own Customize Media clip when he has
 * one, else the game's sound that Enrich kept beside its art. [previewClipUri] is [gameOpeningIntroSources]'s
 * `CustomHeroStore.previewClipUri ?: previewVideoUri`, so this is `previewClipUri ?: launchAudioUri`.
 *
 * [launchAudioUri] is gated to a local file: a `file://` or the library's own `content://`. ScreenScraper media URLs carry
 * credentials and must never be played, logged or persisted from here; the row's projection already refuses them and this
 * refuses them again, because this is the value [playGameOpeningIntro] logs and hands to MediaPlayer.
 *
 * This is the same gate for ANY source of the sound, so it is not gated on `previewAudioEnabled`: that switch belongs to
 * the retired video backdrop's bed, and the clip branch here has never read it either.
 */
internal fun gameOpeningIntroAudioUri(previewClipUri: String?, launchAudioUri: String?): String? =
    previewClipUri?.takeIf { it.isNotBlank() }
        ?: launchAudioUri?.takeIf { it.isNotBlank() && isLocalLaunchAudioUri(it) }

/** A `file://` path or a SAF `content://` document: the only two places a launch sound can live. Never `http(s)://`. */
internal fun isLocalLaunchAudioUri(uri: String): Boolean =
    uri.startsWith("file://", ignoreCase = true) || uri.startsWith("content://", ignoreCase = true)

/**
 * What the intro's start line says about the sound: a kind, never the URI. The path names the game, and a URI that ever
 * was not local would carry credentials, so the log says only `none`, `file`, `content` or `other`.
 */
internal fun gameOpeningIntroAudioLogLabel(audioUri: String?): String = when {
    audioUri == null -> "none"
    audioUri.startsWith("file://", ignoreCase = true) -> "file"
    audioUri.startsWith("content://", ignoreCase = true) -> "content"
    else -> "other"
}

/**
 * The level the launch sound plays at (Settings > Music > Launch sound), or null when there is to be no
 * sound at all: the switch is off, or the volume is dialled to zero (no player is built for a silent sound).
 * Read ONCE when the intro's audio starts and kept for the whole intro, so the entrance and both exits (the intro's own
 * fade and the launch cover's) are scaled by the same number and a dial turned meanwhile cannot make one of them jump.
 */
internal fun gameOpeningIntroAudioTarget(launchSoundEnabled: Boolean, launchSoundVolume: Float): Float? =
    if (launchSoundEnabled) launchSoundVolume.coerceIn(0f, 1f).takeIf { it > 0f } else null

/** One step of a ramp or fade: the [target] level times how far through it the intro is ([fraction], 0 to 1). */
internal fun gameOpeningIntroAudioVolume(target: Float, fraction: Float): Float =
    (target.coerceIn(0f, 1f) * fraction.coerceIn(0f, 1f)).coerceIn(0f, 1f)

internal fun gameOpeningIntroSkipReason(
    game: GameRow,
    placement: MimirGamePlacement,
    enabled: Boolean,
    secondaryDisplayId: Int? = null,
): String? = when {
    !enabled -> "disabled"
    placement == MimirGamePlacement.BOTTOM -> "bottom-placement"
    secondaryDisplayId != null -> "secondary-display"
    game.heroArtUri.isNullOrBlank() -> "no-hero"
    else -> null
}

internal fun gameOpeningPresentationShouldDismiss(
    currentGameId: String?,
    launching: Boolean,
    @Suppress("UNUSED_PARAMETER") closedForLaunch: Boolean = false,
): Boolean = currentGameId != null && !launching

internal fun gameOpeningLaunchTransitionAllowed(secondaryDisplayId: Int?): Boolean = secondaryDisplayId == null

object GameOpeningIntroState {
    private val _current = MutableStateFlow<GameOpeningIntroPlan?>(null)
    val current: StateFlow<GameOpeningIntroPlan?> = _current.asStateFlow()

    /**
     * Counts the removals that skip the exit animation. The overlay keys its whole layer on it, so a bump drops the layer
     * from composition in one step instead of running [GameOpeningIntroOverlay]'s fade-out. It advances AFTER [current]
     * has been cleared, so a reader that sees the new count sees no plan.
     */
    private val _cutEpoch = MutableStateFlow(0L)
    val cutEpoch: StateFlow<Long> = _cutEpoch.asStateFlow()

    private val lock = Any()
    private var player: MediaPlayer? = null

    /** The level [player] ramps up to and fades down from; set with it by [attachPlayer]. Guarded by [lock]. */
    private var audioTarget = 1f
    private var audioRampJob: Job? = null

    /**
     * Keeps the CARD's looping sound ([presentGameOpening]) in line with the music's pause policy and the Bottom window's liveness
     * (a closed lid previously slept the device while the card's sound looped on). Guarded by [lock]; it holds a voice only
     * while the attached player is a card's, so the launch's own one-shot intro is untouched.
     */
    private val audioGate = OpeningAudioGate(
        log = { Log.i(GameOpeningIntroLogTag, it) },
        onVoiceFailed = { releasePlayerAfterEndLocked() },
    )
    private var audioGateWatch: Job? = null

    /**
     * Stamps every attach (and every removal): a watcher applies a change only while its stamp is the current one, so a collector of
     * an earlier attach that was still waiting on [lock] when a replacement attached cannot act on the replacement's gate.
     */
    private var audioGateGeneration = 0L
    private var audioGateSources: OpeningAudioSources? = null

    /**
     * The Bottom's Presentation window, which it registers from `onCreate` and withdraws at `onStop`: whether it is up and lit is the
     * one fact the music's pause policy does not have. No window registered reads as live (Main, a test: never parked, never dark).
     */
    internal val bottomWindow = MutableStateFlow<CompanionWindowLive?>(null)

    /** Where the gate reads the pause policy. A test seam; production reads `MimirMusicControl.pauseReason`. */
    internal var openingAudioSources: () -> OpeningAudioSources = { OpeningAudioSources.production() }

    /** Builds the opening sound's player, on the main looper ([prepareOpeningPlayer]). A test seam; production builds a plain `MediaPlayer`. */
    internal var openingPlayerFactory: () -> MediaPlayer = { MediaPlayer() }
    private var launching = false
    private var dismissing = false

    /** The game's dispatch has happened, so this intro belongs to a trip out of Mimir that a return must not find. */
    private var dispatched = false

    /** When the current plan was shown (`System.nanoTime`), so the launch cover can ask which frame the intro is on. */
    private var shownAtNanos = 0L

    /** The hero the layer has loaded for the current plan, for the launch cover to draw. Null until the layer reports it. */
    private var heroBitmap: ImageBitmap? = null

    fun show(plan: GameOpeningIntroPlan) {
        synchronized(lock) {
            releasePlayerLocked()
            launching = false
            dismissing = false
            dispatched = false
            shownAtNanos = System.nanoTime()
            heroBitmap = null
            _current.value = plan
        }
    }

    fun hide() {
        synchronized(lock) {
            releasePlayerLocked()
            launching = false
            dismissing = false
            dispatched = false
            heroBitmap = null
            _current.value = null
        }
    }

    /** The layer's hero for [gameIdValue] has loaded; kept only while that intro is the current one. */
    internal fun reportHero(gameIdValue: String, hero: ImageBitmap) {
        synchronized(lock) {
            if (_current.value?.gameIdValue == gameIdValue) heroBitmap = hero
        }
    }

    /**
     * The launch cover's snapshot of [gameIdValue]'s intro as it is right now: the plan's URIs and title, the hero the
     * layer loaded, and the frame the intro's tweens are on ([gameOpeningIntroFrameAt]). Null when that intro is not the
     * one showing or its hero has not loaded. A snapshot is an immutable copy: nothing after this reads this object.
     */
    internal fun coverSnapshotFor(gameIdValue: String, nowNanos: Long = System.nanoTime()): GameLaunchCoverSnapshot? =
        synchronized(lock) {
            val plan = _current.value?.takeIf { it.gameIdValue == gameIdValue } ?: return@synchronized null
            val hero = heroBitmap ?: return@synchronized null
            GameLaunchCoverSnapshot(
                gameIdValue = plan.gameIdValue,
                title = plan.title,
                heroUri = plan.heroUri,
                hero = hero,
                logoUri = plan.logoUri,
                frame = gameOpeningIntroFrameAt((nowNanos - shownAtNanos) / 1_000_000L),
            )
        }

    /**
     * Hands [gameIdValue]'s audio player to the caller WITHOUT releasing it, cancelling its fade-in, and forgets it, so
     * the removal that follows ([hideIfCurrentWithoutExit] releases the player it still holds) leaves it playing. The
     * launch cover takes it at the yield and fades and releases it when the cover ends.
     */
    internal fun detachPlayer(gameIdValue: String): MediaPlayer? = synchronized(lock) {
        if (_current.value?.gameIdValue != gameIdValue) return@synchronized null
        audioRampJob?.cancel()
        audioRampJob = null
        // The cover owns the player from here: the gate must not pause or restart what is no longer this intro's.
        dropAudioGateLocked()
        player?.apply {
            // The intro's listeners go with the ownership: the cover ends and releases its own player (MediaPlayerCoverAudio).
            setOnErrorListener(null)
            setOnCompletionListener(null)
        }
        player.also { player = null }
    }

    /** Called when the launch has been dispatched, so [hideDispatchedForReturn] knows this intro is one it may remove. */
    internal fun markDispatched(gameIdValue: String) {
        synchronized(lock) {
            if (_current.value?.gameIdValue == gameIdValue) dispatched = true
        }
    }

    /**
     * Removes [gameIdValue]'s intro with NO exit animation, and reports whether it was the one showing.
     *
     * For a game that has taken the screen. Mimir is stopped then and Compose's frame clock is paused with it, so a
     * plain [hide] would run the overlay's fade-out when Mimir RESUMES, and the player would see the intro's tail on the
     * way back from the game. This bumps [cutEpoch] instead, which drops the overlay's layer from composition.
     */
    internal fun hideIfCurrentWithoutExit(gameIdValue: String): Boolean = synchronized(lock) {
        if (_current.value?.gameIdValue != gameIdValue) return@synchronized false
        removeWithoutExitLocked()
        true
    }

    /**
     * Mimir has started again. An intro whose launch was dispatched must not be there to greet the player, whatever
     * became of the coroutine that was to remove it (it may have been cancelled with its scope, or the game may have
     * come and gone inside the yield hold). A launch that has not been dispatched yet, and a Bottom card's presentation,
     * are still playing and are left alone.
     */
    internal fun hideDispatchedForReturn(): Boolean = synchronized(lock) {
        if (_current.value == null || !dispatched) return@synchronized false
        removeWithoutExitLocked()
        true
    }

    private fun removeWithoutExitLocked() {
        releasePlayerLocked()
        launching = false
        dismissing = false
        dispatched = false
        heroBitmap = null
        _current.value = null
        _cutEpoch.value = _cutEpoch.value + 1
    }

    fun markLaunching(gameIdValue: String) {
        synchronized(lock) {
            if (_current.value?.gameIdValue == gameIdValue) launching = true
        }
    }

    internal fun clearLaunching(gameIdValue: String) {
        synchronized(lock) {
            if (_current.value?.gameIdValue == gameIdValue) launching = false
        }
    }

    internal fun isLaunching(): Boolean = synchronized(lock) { launching }

    internal fun beginDismiss(gameIdValue: String): Boolean {
        val accepted = synchronized(lock) {
            if (_current.value?.gameIdValue != gameIdValue || launching || dismissing) {
                false
            } else {
                dismissing = true
                true
            }
        }
        return accepted
    }

    /**
     * [presentation] is the card's sound ([presentGameOpening]): the gate then owns its start, pause and resume, and a sound
     * that is not allowed to sound right now (screen off, window parked) is attached silent. Null is the launch's one-shot intro,
     * which the caller has already started and which nothing here pauses. A player already attached is released, never stacked.
     */
    internal fun attachPlayer(
        gameIdValue: String,
        candidate: MediaPlayer,
        targetVolume: Float = 1f,
        presentation: OpeningAudioVoice? = null,
    ): Boolean {
        val accepted = synchronized(lock) {
            if (_current.value?.gameIdValue == gameIdValue) {
                val previous = player
                dropAudioGateLocked()
                if (previous != null && previous !== candidate) releaseOnMainLooper(previous)
                player = candidate
                audioTarget = targetVolume.coerceIn(0f, 1f)
                // The player's own word that it has ended: a native error is DELIVERED here, nothing is thrown, so without these the
                // gate would wait on a sound that has gone. Both end the sound through the one path a throwing call takes. Installed
                // before the gate adopts the voice, so no report can slip past an adopted voice.
                candidate.setOnErrorListener { ended, what, extra ->
                    onPlayerEnded(ended, openingAudioNativeReason(what, extra))
                    true
                }
                candidate.setOnCompletionListener { ended -> onPlayerEnded(ended, "completed") }
                if (presentation != null) adoptAudioGateLocked(presentation)
                true
            } else {
                false
            }
        }
        if (!accepted) releaseOnMainLooper(candidate)
        return accepted
    }

    /** Whether [gameIdValue]'s card sound is sounding now (not held by the pause policy), for the caller's `audio started` line. */
    internal fun cardAudioSounding(gameIdValue: String): Boolean = synchronized(lock) {
        _current.value?.gameIdValue == gameIdValue && audioGate.isSounding
    }

    /**
     * Ends the card's looping sound for good, whatever is showing: its window is gone, or a game is launching somewhere else. Nothing
     * resumes it later (the voice is dropped, the player released). No-op when no card sound is attached, so the launch's own intro
     * sound is never touched by it.
     */
    internal fun stopCardAudio(reason: String) {
        synchronized(lock) {
            if (!audioGate.hasVoice) return
            Log.i(GameOpeningIntroLogTag, "opening stop reason=$reason")
            dropAudioGateLocked()
            audioRampJob?.cancel()
            audioRampJob = null
            player?.let { releaseOnMainLooper(it) }
            player = null
            audioTarget = 1f
        }
    }

    /**
     * [ended], the attached player, has said through its own listeners that it cannot go on (a native `MEDIA_ERROR`, or a sound that ran
     * out) and nothing was thrown. It ends exactly as a call that throws does: the card's voice is dropped (the gate stops waiting on a
     * sound that is gone) and the player is released once. Only the CURRENT player counts: one that was replaced, taken by the launch
     * cover or already released belongs to whoever has it now, so a late or repeated report changes nothing and releases nothing.
     * Called on the main looper, where the player delivers its events.
     */
    internal fun onPlayerEnded(ended: MediaPlayer, reason: String) {
        synchronized(lock) {
            if (player !== ended) return
            if (audioGate.hasVoice) {
                audioGate.fail(reason)
            } else {
                // The launch's one-shot intro: no gate holds it, so it is released here.
                Log.i(GameOpeningIntroLogTag, "opening audio ended reason=$reason")
                releasePlayerAfterEndLocked()
            }
        }
    }

    private fun windowLiveNow(): Boolean = bottomWindow.value?.isLive ?: true

    /**
     * Seeds the gate with the inputs as they are now (so a start never precedes the decision) and follows them until the voice goes.
     * Called on the main looper (the attach is), under [lock]. A voice that fails its very first start is released by the gate's
     * [OpeningAudioGate] callback and nothing is watched for it.
     */
    private fun adoptAudioGateLocked(voice: OpeningAudioVoice) {
        val sources = openingAudioSources()
        val generation = ++audioGateGeneration
        audioGateSources = sources
        audioGate.adopt(voice, sources.musicPause.value, windowLiveNow())
        if (!audioGate.hasVoice) return
        audioGateWatch?.cancel()
        audioGateWatch = startAudioGateWatch(sources, generation)
        if (!audioGate.hasVoice) {
            audioGateWatch?.cancel()
            audioGateWatch = null
        }
    }

    // Each change is only a prompt: the watcher applies nothing it was handed, it re-reads BOTH inputs under the lock (and checks its
    // stamp), so a stale reading cannot resume a replacement card while the Bottom is parked or dark.
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun startAudioGateWatch(sources: OpeningAudioSources, generation: Long): Job = sources.scope.launch {
        launch {
            sources.musicPause.collect { reevaluateAudioGate(generation) }
        }
        launch {
            bottomWindow
                .flatMapLatest { window -> window?.liveFlow ?: flowOf(true) }
                .collect { reevaluateAudioGate(generation) }
        }
    }

    /**
     * Applies the CURRENT pause reason and the CURRENT window liveness to the gate, but only for the attach stamped [generation]. Both
     * are read here, under the lock, whatever the caller was handed.
     */
    internal fun reevaluateAudioGate(generation: Long) {
        synchronized(lock) {
            if (generation != audioGateGeneration) return
            val sources = audioGateSources ?: return
            audioGate.refresh(sources.musicPause.value, windowLiveNow())
        }
    }

    /** The stamp of the attach that is current, for the watcher's own check (and a test's). */
    internal val currentAudioGateGeneration: Long get() = synchronized(lock) { audioGateGeneration }

    private fun dropAudioGateLocked() {
        audioGateGeneration++
        audioGate.drop()
        audioGateWatch?.cancel()
        audioGateWatch = null
    }

    /**
     * The attached player has ended for good, so nothing more can be asked of it: the card's voice could not start or hold (the player
     * threw), or the player reported a native error or ran out through its own listeners ([onPlayerEnded]). Its voice is already
     * dropped and the player is released here, exactly once (the reference is cleared with it), never left to sound on or waited on.
     * Called under [lock].
     */
    private fun releasePlayerAfterEndLocked() {
        audioGateGeneration++
        audioGateWatch?.cancel()
        audioGateWatch = null
        audioRampJob?.cancel()
        audioRampJob = null
        player?.let { releaseOnMainLooper(it) }
        player = null
        audioTarget = 1f
    }

    /** The level the sound of [gameIdValue] is playing at, for the launch cover to fade down from; 1 when nothing is attached. */
    internal fun audioTargetFor(gameIdValue: String): Float = synchronized(lock) {
        if (_current.value?.gameIdValue == gameIdValue && player != null) audioTarget else 1f
    }

    internal fun startAudioRamp(scope: CoroutineScope, gameIdValue: String) {
        val job = scope.launch {
            val stepDelayMs = GameOpeningIntroAudioRampMs / GameOpeningIntroAudioRampSteps
            repeat(GameOpeningIntroAudioRampSteps) { step ->
                delay(stepDelayMs)
                var target = 1f
                val activePlayer = synchronized(lock) {
                    target = audioTarget
                    player.takeIf { _current.value?.gameIdValue == gameIdValue }
                } ?: return@launch
                val volume = gameOpeningIntroAudioVolume(target, (step + 1) / GameOpeningIntroAudioRampSteps.toFloat())
                if (!setVolumeOnMainLooper(activePlayer, volume)) return@launch
            }
        }
        synchronized(lock) {
            if (_current.value?.gameIdValue == gameIdValue) {
                audioRampJob?.cancel()
                audioRampJob = job
            } else {
                job.cancel()
            }
        }
    }

    internal suspend fun fadeAudioOut(gameIdValue: String, durationMs: Long) {
        var target = 1f
        val activePlayer = synchronized(lock) {
            if (_current.value?.gameIdValue == gameIdValue) {
                audioRampJob?.cancel()
                audioRampJob = null
                target = audioTarget
                player
            } else {
                null
            }
        }
        val stepDelayMs = durationMs / GameOpeningIntroAudioRampSteps
        repeat(GameOpeningIntroAudioRampSteps) { step ->
            activePlayer?.let { mediaPlayer ->
                val volume = gameOpeningIntroAudioVolume(target, 1f - (step + 1) / GameOpeningIntroAudioRampSteps.toFloat())
                setVolumeOnMainLooper(mediaPlayer, volume)
            }
            delay(stepDelayMs)
        }
    }

    internal fun hideIfCurrent(gameIdValue: String) {
        synchronized(lock) {
            if (_current.value?.gameIdValue == gameIdValue) {
                releasePlayerLocked()
                launching = false
                dismissing = false
                dispatched = false
                heroBitmap = null
                _current.value = null
            }
        }
    }

    private fun releasePlayerLocked() {
        dropAudioGateLocked()
        audioRampJob?.cancel()
        audioRampJob = null
        player?.let { releaseOnMainLooper(it) }
        player = null
        audioTarget = 1f
    }
}

/**
 * The one plan-or-skip step every emulated launch on
 * Main runs before [playGameOpeningIntro]: Home's row, Home's launch card and every Library launch (through
 * [GameOpeningIntroLibraryLaunchOpening]). Same gates, same logged skip, same hold. Returns the plan it played, or
 * null after logging why it skipped, so a caller finishes only an intro that started.
 */
internal suspend fun beginGameOpeningIntro(
    context: Context,
    game: GameRow,
    secondaryDisplayId: Int?,
    enabled: Boolean = gameOpeningIntroEnabled(),
    sources: (GameRow) -> GameRow = { row -> gameOpeningIntroSources(context, row) },
): GameOpeningIntroPlan? {
    val introGame = sources(game)
    val plan = if (gameOpeningLaunchTransitionAllowed(secondaryDisplayId)) {
        gameOpeningIntroPlan(introGame, MimirGamePlacement.MAIN, enabled)
    } else {
        null
    }
    if (plan == null) {
        gameOpeningIntroSkipReason(introGame, MimirGamePlacement.MAIN, enabled, secondaryDisplayId)?.let { reason ->
            Log.i(GameOpeningIntroLogTag, "skip game=${game.gameId.value} reason=$reason")
        }
        // A game is launching and this launch plays no intro of its own (a Bottom display is up, say): a card's looping sound left
        // from an open launch card must not play on over it. A launch that does play one replaces the card's sound through `show`.
        GameOpeningIntroState.stopCardAudio("game-launch")
        return null
    }
    playGameOpeningIntro(context, plan)
    return plan
}

/**
 * The Library's side of the one intro entry. [LibraryViewModel.launch] calls [beforeDispatch] after its
 * session admission and its return-target publish and before the port dispatches, so the intro holds for launch as
 * Home's does; [afterDispatch] finishes it with the dispatch verdict, so a failed launch cuts it short, gives the
 * menu music back and leaves nothing set that stops the next intro. The overlay is app-level
 * ([GameOpeningIntroOverlay] in MimirAppHost, above the destination host and the overlay host), so it draws over the
 * Library's surfaces with no change. [introContext] is Main in production, as Home runs it; tests pass their own, and
 * pass their own [finish] to drive what the launch waits for.
 */
internal class GameOpeningIntroLibraryLaunchOpening(
    private val context: Context,
    private val secondaryDisplayId: () -> Int?,
    private val enabled: () -> Boolean = ::gameOpeningIntroEnabled,
    private val sources: (GameRow) -> GameRow = { row -> gameOpeningIntroSources(context, row) },
    private val introContext: CoroutineContext = Dispatchers.Main.immediate,
    private val finish: (CoroutineScope, Boolean) -> Unit = { scope, launched ->
        finishGameOpeningIntro(scope, launched = launched)
    },
) : LibraryLaunchOpening {
    @Volatile
    private var openedGameIdValue: String? = null

    override suspend fun beforeDispatch(game: GameRow) {
        openedGameIdValue = null
        val plan = withContext(introContext) {
            beginGameOpeningIntro(context, game, secondaryDisplayId(), enabled(), sources)
        }
        if (plan != null) openedGameIdValue = plan.gameIdValue
    }

    override fun afterDispatch(scope: CoroutineScope, game: GameRow, launched: Boolean) {
        if (openedGameIdValue != game.gameId.value) return
        openedGameIdValue = null
        finish(scope, launched)
    }
}

suspend fun playGameOpeningIntro(context: Context, plan: GameOpeningIntroPlan) {
    runCatching {
        Log.i(
            GameOpeningIntroLogTag,
            "start game=${plan.gameIdValue} hero=${plan.heroUri} logo=${plan.logoUri} " +
                "audio=${gameOpeningIntroAudioLogLabel(plan.audioUri)}",
        )
        GameOpeningIntroState.show(plan)
        gameOpeningIntroPreMeasureLogo(context, plan)
        MimirFrontendMusic.onAppBackgrounded()
        startGameOpeningIntroAudio(context, plan, looping = false)
        Log.i(GameOpeningIntroLogTag, "hold-for-launch game=${plan.gameIdValue}")
        delay(GameOpeningIntroHoldMs)
    }.onFailure { failure ->
        Log.w(
            GameOpeningIntroLogTag,
            "intro unavailable reason=${failure.message ?: failure.javaClass.simpleName}",
        )
    }
}

/**
 * A launch card opened on the Bottom: Main's opening intro plays under it, its sound LOOPING until the card closes.
 *
 * That sound follows the music's one pause policy ([OpeningAudioGate]; `MimirMusicControl.pauseReason`) and the Bottom window's
 * liveness: a closed lid or screen-off, unplugged headphones, a call or another app holding the audio, the player's own pause,
 * and a window parked behind another app hold it; it resumes only while this card is still the open one. It ends for good when
 * the card closes, the window ends, or a game launches elsewhere ([GameOpeningIntroState.stopCardAudio]).
 */
suspend fun presentGameOpening(context: Context, plan: GameOpeningIntroPlan) {
    runCatching {
        if (GameOpeningIntroState.current.value?.gameIdValue == plan.gameIdValue) return@runCatching
        Log.i(GameOpeningIntroLogTag, "present game=${plan.gameIdValue}")
        GameOpeningIntroState.show(plan)
        gameOpeningIntroPreMeasureLogo(context, plan)
        MimirFrontendMusic.onAppBackgrounded()
        startGameOpeningIntroAudio(context, plan, looping = true)
    }.onFailure { failure ->
        Log.w(
            GameOpeningIntroLogTag,
            "presentation unavailable reason=${failure.message ?: failure.javaClass.simpleName}",
        )
    }
}

/**
 * The original implementation awaited
 * [MimirArtInkStore.measure] BEFORE [GameOpeningIntroState.show], to make the logo's first frame
 * ink-correct. [MimirArtInkStore.measure] hops to [kotlinx.coroutines.Dispatchers.IO]
 * (`MimirArtInk.kt:209`) whenever the logo is unmeasured — every logo's first show per install, every
 * visit of an `Unmeasurable` logo, and the first [MimirArtInkStore.ensureLoaded] per process — so
 * awaiting it ahead of `show` let a later-launched [dismissGameOpeningPresentation] run to completion
 * first (it found `current == null` and returned) and THEN have `show` post a plan with no card open;
 * two overlapping calls could also show whichever's measurement finished last, not whichever card is
 * actually open on the Bottom.
 *
 * Fired on its own coroutine, never awaited by the caller, so a still-decoding logo cannot delay or
 * skip `show`. The one cost, accepted here as it already is on the Now Playing card (spec-R6-P2-09.md
 * §4): a never-before-measured logo's frame 0 may still paint plain-`Fit`-of-canvas and snap once ink
 * lands, because [rememberMimirLogoInk] on the composing side re-measures (a no-op set lookup) and
 * recomposes when [MimirArtInkStore.boundsFor] updates regardless of whether this pre-measure won the
 * race. `runCatching` here (rather than the caller's, which now runs before this is even launched)
 * keeps a measurement failure from reaching an uncaught-exception handler on whatever job this
 * coroutine's parent carries.
 */
private suspend fun gameOpeningIntroPreMeasureLogo(context: Context, plan: GameOpeningIntroPlan) {
    val logoUri = plan.logoUri ?: return
    CoroutineScope(currentCoroutineContext()).launch {
        runCatching {
            MimirArtInkStore.ensureLoaded(context)
            MimirArtInkStore.measure(context, logoUri)
        }.onFailure { failure ->
            Log.w(
                GameOpeningIntroLogTag,
                "logo ink pre-measure unavailable reason=${failure.message ?: failure.javaClass.simpleName}",
            )
        }
    }
}

fun dismissGameOpeningPresentation(scope: CoroutineScope, reason: String) {
    val current = GameOpeningIntroState.current.value ?: return
    if (!gameOpeningPresentationShouldDismiss(current.gameIdValue, GameOpeningIntroState.isLaunching())) return
    if (!GameOpeningIntroState.beginDismiss(current.gameIdValue)) return
    scope.launch {
        runCatching {
            Log.i(GameOpeningIntroLogTag, "dismiss game=${current.gameIdValue} reason=$reason")
            GameOpeningIntroState.fadeAudioOut(current.gameIdValue, GameOpeningIntroExitMs.toLong())
            if (gameOpeningPresentationShouldDismiss(
                    GameOpeningIntroState.current.value?.gameIdValue,
                    GameOpeningIntroState.isLaunching(),
                )
            ) {
                GameOpeningIntroState.hide()
                MimirFrontendMusic.onAppForegrounded()
            }
        }.onFailure { failure ->
            GameOpeningIntroState.hideIfCurrent(current.gameIdValue)
            MimirFrontendMusic.onAppForegrounded()
            Log.w(
                GameOpeningIntroLogTag,
                "presentation finish unavailable reason=${failure.message ?: failure.javaClass.simpleName}",
            )
        }
    }
}

/**
 * What [finishGameOpeningIntro] tells the launch cover, which is process-wide and armed before the intent fires.
 * [GameLaunchCover] is the real one; a test that drives [finishGameOpeningIntro] passes its own.
 */
internal interface GameLaunchCoverHandoff {
    /**
     * Mimir has stopped. True when a cover is live for [gameIdValue], in which case it has taken the audio player
     * [takePlayer] returns (or had none to take) and the caller must not fade the audio itself; false when there is no
     * cover and the caller does what it always did. [takePlayer] runs before this returns, on the caller's thread, because
     * the caller releases the player it still holds as soon as it is back.
     *
     * [startVolume] is the level the intro's sound is playing at (Settings > Music > Launch sound volume): the cover fades
     * the player down FROM it, so a sound dialled to 40% never jumps back up to full as the cover takes it over.
     */
    fun yielded(gameIdValue: String, startVolume: Float = 1f, takePlayer: () -> MediaPlayer?): Boolean

    /** The launch of [gameIdValue] did not take Mimir's screen (a verdict, the cap, a dispatch that failed). Ends the cover at once. */
    fun settled(gameIdValue: String)
}

/** What ended a dispatched launch's wait for the game to take the screen; [logName] is its `via=` in the log. */
internal enum class GameOpeningIntroHandoff(val logName: String) {
    /**
     * (a) Mimir has stopped, so the game holds the window. Read from the session ledger's yield edge
     * (`MainActivity.onStop` -> `gameYielded`, DISPATCHED to YIELDED_TO_SECONDARY_TASK) and from
     * [PrimaryActivityObservationState.stopped], which the same `onStop` sets.
     */
    GAME_HAS_SCREEN("yield"),

    /**
     * (b) The ledger settled the launch without the game taking Mimir's window: the dispatch watchdog's verdict
     * (RECOVERING, then FAILED, when the target never left the primary within [GameDispatchPrimaryGraceMs]), or a
     * launch that has already returned or ended.
     */
    SESSION_SETTLED("verdict"),

    /** (c) Neither came within [GameOpeningIntroHandoffCapMs]. */
    CAP("cap"),
}

/**
 * Where the launch of [gameIdValue] stands, or null while it is still waiting for the game to take the screen.
 *
 * [hostStopped] comes first and stands on its own. The ledger's `gameYielded` only acts on a DISPATCHED session, and
 * every caller publishes DISPATCHED after `startActivity` returns (the Tweaker's post-dispatch hook and the elapsed
 * store run in between). A game that covers Mimir before that chain finishes stops Mimir while the phase is still
 * PREPARING, `gameYielded` drops the edge, and the session then reads DISPATCHED with Mimir already gone: the watchdog
 * never expires (Mimir does not own the window) and only the cap would end the wait, over a game that has been on screen
 * for seconds. `onStop` sets [hostStopped] whatever the phase was.
 */
internal fun gameOpeningIntroHandoffOf(
    snapshot: MimirSessionSnapshot,
    hostStopped: Boolean,
    gameIdValue: String,
): GameOpeningIntroHandoff? {
    if (hostStopped) return GameOpeningIntroHandoff.GAME_HAS_SCREEN
    val game = snapshot.gameSession?.takeIf { it.gameId.value == gameIdValue }
        ?: return GameOpeningIntroHandoff.SESSION_SETTLED
    return when (game.phase) {
        MimirSessionPhase.PREPARING,
        MimirSessionPhase.DISPATCHED,
        -> null
        MimirSessionPhase.FOREGROUND_PROVEN,
        MimirSessionPhase.YIELDED_TO_SECONDARY_TASK,
        -> GameOpeningIntroHandoff.GAME_HAS_SCREEN
        MimirSessionPhase.RETURNING,
        MimirSessionPhase.RECOVERING,
        MimirSessionPhase.ENDED,
        MimirSessionPhase.FAILED,
        MimirSessionPhase.STALE,
        -> GameOpeningIntroHandoff.SESSION_SETTLED
    }
}

/** Waits for the first of (a), (b) and the cap (c), whichever [gameOpeningIntroHandoffOf] reads first. */
internal suspend fun awaitGameOpeningIntroHandoff(
    gameIdValue: String,
    sessions: StateFlow<MimirSessionSnapshot> = MimirSessionAuthority.state,
    hostStopped: StateFlow<Boolean> = PrimaryActivityObservationState.stopped,
    capMs: Long = GameOpeningIntroHandoffCapMs,
): GameOpeningIntroHandoff = withTimeoutOrNull(capMs) {
    combine(sessions, hostStopped) { snapshot, stopped ->
        gameOpeningIntroHandoffOf(snapshot, stopped, gameIdValue)
    }.first { it != null }
} ?: GameOpeningIntroHandoff.CAP

/**
 * Ends the intro after dispatch, holding 0.2 seconds after the game gains the screen. It used to
 * use a blind timer, 2.5 s and then the fade, and an emulator that took
 * longer than about 2.9 s to cover Mimir was exposed underneath. Now it waits for the FIRST of:
 *
 *  - (a) the game has the screen ([GameOpeningIntroHandoff.GAME_HAS_SCREEN]). The intro holds [GameOpeningIntroYieldHoldMs]
 *    more, the audio fading across it, and is removed with NO exit animation. Mimir is stopped by then and Compose's
 *    frame clock is paused, so a fade-out started now would run when Mimir RESUMES and the player would see the intro's
 *    tail on the way back from the game. [GameOpeningIntroState.hideIfCurrentWithoutExit] takes the layer out of
 *    composition instead. The hold is a plain `delay` and not `mimirHold`, on purpose: `mimirHold` waits on the
 *    animation clock, which is the clock that is paused here, so it would not return until the player came back.
 *  - (b) the ledger settled the launch ([GameOpeningIntroHandoff.SESSION_SETTLED]), which is the dispatch watchdog's
 *    verdict when the target never left the primary. Mimir kept the window, so the intro fades as it always has, the
 *    audio and then the picture, over whatever Mimir now shows.
 *  - (c) [GameOpeningIntroHandoffCapMs] ([GameOpeningIntroHandoff.CAP]), the same fade, for a launch that neither
 *    yields nor settles (an emulator slow enough to keep Mimir paused past the watchdog's window).
 *
 * The intro draws above the overlay host, so it must never sit over what the watchdog hands back: (b) begins the fade at
 * the verdict, not at the cap.
 *
 * A fade leaves Mimir on screen, so it gives the frontend music back, as a failed launch does; (a) does not, because
 * the game backgrounded Mimir and its real foreground edge (`onStart`) restores it. A launch that never dispatched
 * ([launched] false) has nothing to cover and fades at once, unchanged.
 *
 * Every caller reaches this after publishing DISPATCHED: Home's launch card and row, the Library's `afterDispatch` and
 * the Bottom screen's pick. [GameOpeningIntroState.markDispatched] records that here, so `MainActivity.onStart` can
 * remove the intro if the coroutine below never runs to its end.
 *
 * A launch cover ([GameLaunchCover], armed before the intent for Dolphin and ARMSX2) changes two things, and only when one
 * is live for this game. At the yield the cover takes the audio player ([GameOpeningIntroState.detachPlayer]) and the
 * intro leaves at once, since the cover is what stays on screen and ends with the game's own screen; the 0.2 s audio fade
 * above is then the cover's to do. On every other ending (the verdict, the cap, a launch that never dispatched, a failed
 * wait) Mimir keeps the window, so the cover is ended first and the intro's own fade is what the player sees.
 *
 * [awaitHandoff], [hostStopped], [restoreMenuMusic] and [cover] are injectable for tests; the defaults read the process's own.
 */
internal fun finishGameOpeningIntro(
    scope: CoroutineScope,
    launched: Boolean = true,
    awaitHandoff: suspend (gameIdValue: String) -> GameOpeningIntroHandoff = { gameIdValue ->
        awaitGameOpeningIntroHandoff(gameIdValue)
    },
    hostStopped: () -> Boolean = { PrimaryActivityObservationState.stopped.value },
    restoreMenuMusic: () -> Unit = { MimirFrontendMusic.onAppForegrounded() },
    cover: GameLaunchCoverHandoff = GameLaunchCover,
) {
    val plan = GameOpeningIntroState.current.value ?: return
    if (launched) GameOpeningIntroState.markDispatched(plan.gameIdValue)
    // ATOMIC, and the check below, for a launch whose owner is already gone (launch-return, Recents during the
    // 3 s hold, and the Activity that began the launch is destroyed before the dispatch finishes). The dispatch runs in
    // NonCancellable so the game still opens, but a launch on a cancelled scope never ran its body: the intro, which is
    // process-wide and was marked dispatched AFTER the new Mimir copy's onStart had already looked, stayed over that copy.
    // The body now always starts, finds the scope cancelled, and takes the failure path: end the intro, give the music back.
    scope.launch(start = CoroutineStart.ATOMIC) {
        runCatching {
            currentCoroutineContext().ensureActive()
            val handoff = if (launched) awaitHandoff(plan.gameIdValue) else null
            if (handoff != GameOpeningIntroHandoff.GAME_HAS_SCREEN) cover.settled(plan.gameIdValue)
            if (GameOpeningIntroState.current.value?.gameIdValue != plan.gameIdValue) return@runCatching
            if (handoff != null) {
                Log.i(GameOpeningIntroLogTag, "handoff game=${plan.gameIdValue} via=${handoff.logName}")
            }
            if (handoff == GameOpeningIntroHandoff.GAME_HAS_SCREEN) {
                // Under a cover the audio is the cover's and the intro goes now; without one the audio fade IS the hold:
                // the picture and the music end together, 0.2 s after the game came up.
                val coverHasIt = cover.yielded(plan.gameIdValue, GameOpeningIntroState.audioTargetFor(plan.gameIdValue)) {
                    GameOpeningIntroState.detachPlayer(plan.gameIdValue)
                }
                if (!coverHasIt) GameOpeningIntroState.fadeAudioOut(plan.gameIdValue, GameOpeningIntroYieldHoldMs)
                if (GameOpeningIntroState.hideIfCurrentWithoutExit(plan.gameIdValue)) {
                    Log.i(GameOpeningIntroLogTag, "end game=${plan.gameIdValue} launched=true via=${handoff.logName}")
                }
                return@runCatching
            }
            GameOpeningIntroState.fadeAudioOut(plan.gameIdValue, GameOpeningIntroExitMs.toLong())
            if (GameOpeningIntroState.current.value?.gameIdValue == plan.gameIdValue) {
                // Mimir can have stopped while the audio faded; a picture fade begun then would run on the way back.
                if (hostStopped()) {
                    GameOpeningIntroState.hideIfCurrentWithoutExit(plan.gameIdValue)
                } else {
                    GameOpeningIntroState.hide()
                }
                Log.i(
                    GameOpeningIntroLogTag,
                    "end game=${plan.gameIdValue} launched=$launched" + (handoff?.let { " via=${it.logName}" } ?: ""),
                )
            }
            // A fade leaves Mimir showing, so the music the intro switched off comes back. The game's own
            // backgrounding of Mimir restores it in (a); here nothing else would.
            if (!hostStopped()) restoreMenuMusic()
        }.onFailure { failure ->
            val stopped = hostStopped()
            if (stopped) {
                // The game has the screen, so a live cover stays: its own ends (the game, a foreign screen, the return,
                // the ceiling) do not depend on this wait.
                GameOpeningIntroState.hideIfCurrentWithoutExit(plan.gameIdValue)
            } else {
                runCatching { cover.settled(plan.gameIdValue) }
                GameOpeningIntroState.hideIfCurrent(plan.gameIdValue)
                restoreMenuMusic()
            }
            Log.w(
                GameOpeningIntroLogTag,
                "intro finish unavailable reason=${failure.message ?: failure.javaClass.simpleName}",
            )
        }
    }
}

private suspend fun startGameOpeningIntroAudio(
    context: Context,
    plan: GameOpeningIntroPlan,
    looping: Boolean,
) {
    // Settings > Music > Launch sound. Read here, where BOTH starts (the intro and the Bottom presentation) pass, so neither can
    // forget it, and read once: the visual intro is untouched either way, and only the game's sound is skipped.
    val target = gameOpeningIntroAudioTarget(
        launchSoundEnabled = AudioPreferences.launchSoundEnabled.value,
        launchSoundVolume = AudioPreferences.launchSoundVolume.value,
    ) ?: run {
        val reason = if (AudioPreferences.launchSoundEnabled.value) "launch-sound-volume-zero" else "launch-sound-off"
        Log.i(GameOpeningIntroLogTag, "audio unavailable reason=$reason")
        return
    }
    val audioUri = plan.audioUri ?: run {
        Log.i(GameOpeningIntroLogTag, "audio unavailable reason=missing-uri")
        return
    }
    // EVERY call on the player is made on the main looper: it is built, given its source and prepared
    // there (`prepareAsync`, awaited: never `prepare()` on a worker thread and then used on Main), and the attach, which starts the
    // card's sound through the gate, the ramp, the fades and the gate's own watcher all run there too, so MediaPlayer is never called
    // from two threads and a release cannot race a start. The ramp's scope is taken HERE, before the switch, so the caller's job still
    // owns it and this function returns as soon as the sound is attached, as it always did.
    val rampScope = CoroutineScope(currentCoroutineContext() + Dispatchers.Main.immediate)
    withContext(Dispatchers.Main.immediate) {
        val mediaPlayer = try {
            prepareOpeningPlayer(context, audioUri, looping, GameOpeningIntroState.openingPlayerFactory)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.i(
                GameOpeningIntroLogTag,
                "audio unavailable reason=${failure.message ?: failure.javaClass.simpleName}",
            )
            return@withContext
        }
        runCatching {
            mediaPlayer.setVolume(0f, 0f)
            // The card's looping sound (looping = true) is NOT started here: the gate starts it inside the attach, only if the music's
            // pause policy and the Bottom window allow it at that moment, so a card opened with the screen off never plays and a closed
            // lid pauses it. The launch's one-shot intro starts here as it always did.
            if (!looping) {
                mediaPlayer.start()
                Log.i(GameOpeningIntroLogTag, "audio started durationMs=${mediaPlayer.duration}")
            }
            val presentation = if (looping) MediaPlayerOpeningVoice(mediaPlayer) else null
            if (GameOpeningIntroState.attachPlayer(plan.gameIdValue, mediaPlayer, target, presentation)) {
                if (looping && GameOpeningIntroState.cardAudioSounding(plan.gameIdValue)) {
                    Log.i(GameOpeningIntroLogTag, "audio started durationMs=${mediaPlayer.duration}")
                }
                GameOpeningIntroState.startAudioRamp(
                    scope = rampScope,
                    gameIdValue = plan.gameIdValue,
                )
            }
        }.onFailure { failure ->
            releaseOnMainLooper(mediaPlayer)
            Log.i(
                GameOpeningIntroLogTag,
                "audio unavailable reason=${failure.message ?: failure.javaClass.simpleName}",
            )
        }
    }
}

@Composable
fun GameOpeningIntroOverlay() {
    // A removal WITHOUT an exit (a game that has taken the screen) bumps this epoch, and `key` drops the whole layer, its
    // retained plan and its exit clock with it, in the one recomposition. That recomposition runs before the first frame
    // after Mimir returns, so no frame of the intro is drawn on the way back. A `visible = false` flip is not the same:
    // AnimatedVisibility would run its fade-out when the paused frame clock resumes, and even `ExitTransition.None` needs
    // a frame to finish and would draw the intro once more (see GameOpeningIntroState.hideIfCurrentWithoutExit).
    val cutEpoch by GameOpeningIntroState.cutEpoch.collectAsState()
    key(cutEpoch) { GameOpeningIntroLayer() }
}

@Composable
private fun GameOpeningIntroLayer() {
    val plan by GameOpeningIntroState.current.collectAsState()
    var displayedPlan by remember { mutableStateOf<GameOpeningIntroPlan?>(null) }
    LaunchedEffect(plan) {
        if (plan != null) {
            displayedPlan = plan
        } else {
            delay(GameOpeningIntroExitMs.toLong())
            if (GameOpeningIntroState.current.value == null) displayedPlan = null
        }
    }

    AnimatedVisibility(
        visible = plan != null,
        enter = EnterTransition.None,
        exit = fadeOut(tween(GameOpeningIntroExitMs, easing = MimirMotion.Easing)),
        modifier = Modifier
            .fillMaxSize()
            .testTag("game-opening-intro"),
    ) {
        val shown = displayedPlan ?: return@AnimatedVisibility
        val heroAlpha = remember(shown.gameIdValue) { Animatable(0f) }
        val logoAlpha = remember(shown.gameIdValue) { Animatable(0f) }
        val logoScale = remember(shown.gameIdValue) { Animatable(GameOpeningIntroLogoScaleFrom) }
        val kenBurnsScale = remember(shown.gameIdValue) { Animatable(1f) }
        LaunchedEffect(shown.gameIdValue) {
            launch {
                heroAlpha.animateTo(
                    1f,
                    tween(GameOpeningIntroEnterMs, easing = MimirMotion.Easing),
                )
            }
            launch {
                logoAlpha.animateTo(
                    1f,
                    tween(GameOpeningIntroEnterMs, easing = MimirMotion.Easing),
                )
            }
            launch {
                logoScale.animateTo(
                    1f,
                    tween(GameOpeningIntroEnterMs, easing = MimirMotion.Easing),
                )
            }
            kenBurnsScale.animateTo(
                GameOpeningIntroZoomTo,
                tween(GameOpeningIntroZoomMs, easing = MimirMotion.Easing),
            )
        }
        GameOpeningIntroFrame(
            title = shown.title,
            logoUri = shown.logoUri,
            heroAlpha = { heroAlpha.value },
            logoAlpha = { logoAlpha.value },
            logoScale = { logoScale.value },
            heroZoom = { kenBurnsScale.value },
            modifier = Modifier.pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent()
                }
            },
        ) { heroModifier ->
            AsyncImage(
                model = shown.heroUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = heroModifier,
                // The launch cover draws this very bitmap (resident in Coil's memory cache), so the frame it takes over
                // from is this one, decoded once.
                onSuccess = { success ->
                    (success.result.drawable as? BitmapDrawable)?.bitmap?.let { bitmap ->
                        GameOpeningIntroState.reportHero(shown.gameIdValue, bitmap.asImageBitmap())
                    }
                },
            )
        }
    }
}

/**
 * One frame of the intro, drawn from plain values: the hero in the [hero] slot (the layer puts an `AsyncImage` there, the
 * launch cover a bitmap it already holds), the ink-fitted logo on the one fixed stage or else the title, and the ground and
 * gradient between. Shared by [GameOpeningIntroLayer] and the launch cover so that the cover is, by construction, the same
 * picture and a change to the intro reaches both. The four animated values are lambdas read in the draw phase, as the
 * layer's `Animatable`s always were; the cover passes constants.
 *
 * [hero] receives the modifier that sizes the hero to the frame and applies [heroAlpha] and [heroZoom].
 */
@Composable
internal fun GameOpeningIntroFrame(
    title: String,
    logoUri: String?,
    heroAlpha: () -> Float,
    logoAlpha: () -> Float,
    logoScale: () -> Float,
    heroZoom: () -> Float,
    modifier: Modifier = Modifier,
    hero: @Composable (Modifier) -> Unit,
) {
    val palette = LocalMimirPalette.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.surfaceBase)
            .then(modifier),
    ) {
        hero(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = heroAlpha()
                    scaleX = heroZoom()
                    scaleY = heroZoom()
                },
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            palette.surfaceBase.copy(alpha = 0f),
                            palette.surfaceBase.copy(alpha = 0.55f),
                        ),
                    ),
                ),
        )
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            logoUri?.let { logo ->
                // the one fixed MimirLogoStage -- was
                // `widthIn(max = maxWidth * 0.44f).heightIn(max = maxHeight * 0.30f)`, a fraction of
                // THIS viewport that differed from the Now Playing card's fixed 430 x 215 dp stage, so
                // the same logo changed size between the transition and the card that followed it.
                // MimirFlatLogoArt's own painter (rememberMimirLocalArtPainter) replaces the bare
                // AsyncImage -- same loader, one identity -- and ink-fits into the stage exactly as
                // the card does.
                MimirFlatLogoArt(
                    title = title,
                    logoUri = logo,
                    ink = rememberMimirLogoInk(logo),
                    modifier = Modifier
                        .size(MimirLogoStage.width, MimirLogoStage.height)
                        .offset(y = GameOpeningIntroLogoOffsetDp.dp)
                        .graphicsLayer {
                            alpha = logoAlpha()
                            scaleX = logoScale()
                            scaleY = logoScale()
                        },
                )
            } ?: Text(
                text = title,
                style = MimirTypeScale.Title,
                color = palette.textPrimary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = maxWidth * 0.44f)
                    .offset(y = GameOpeningIntroLogoOffsetDp.dp)
                    .graphicsLayer {
                        alpha = logoAlpha()
                        scaleX = logoScale()
                        scaleY = logoScale()
                    },
            )
        }
    }
}

internal fun gameOpeningIntroEnabled(): Boolean = runCatching {
    val method = Class.forName("android.os.SystemProperties").getDeclaredMethod(
        "get",
        String::class.java,
        String::class.java,
    )
    method.isAccessible = true
    (method.invoke(null, GameOpeningIntroSystemProperty, "1") as? String) != "0"
}.getOrDefault(true)
