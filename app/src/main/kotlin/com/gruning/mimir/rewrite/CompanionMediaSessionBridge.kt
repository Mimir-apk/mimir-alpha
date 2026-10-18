package com.gruning.mimir.rewrite

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.gruning.mimir.rewrite.core.companionnative.startSettingsSurface
import android.os.SystemClock
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val CompanionMediaArtworkMaxPx = 512

/**
 * Scales a media session's artwork OFF the main looper. The bridge's callbacks arrive on the main looper, where `Bitmap.createScaledBitmap` of a 1000 px album cover
 * cost a frame or more at the very moment the track-swap motion starts. [request] hands the bitmap to [scope] (the default dispatcher), and [deliver] is called from there
 * with the bitmap it was asked about and the scaled copy, or null when the scale failed, so the caller publishes only once the art is ready and drops an answer for a
 * bitmap it has since stopped caring about. [scope] and [scale] are the seams a test passes; the bridge passes neither.
 */
internal class CompanionArtworkScaler(
    private val scope: CoroutineScope = CompanionArtworkScalerScope,
    private val maxPx: Int = CompanionMediaArtworkMaxPx,
    private val scale: (Bitmap, Int) -> Bitmap? = ::scaleCompanionArtwork,
    private val deliver: (source: Bitmap, scaled: Bitmap?) -> Unit,
) {
    /** Whether [bitmap] needs scaling at all (its long edge is over the bound). */
    fun needsScaling(bitmap: Bitmap): Boolean = maxOf(bitmap.width, bitmap.height) > maxPx

    /** Scales [bitmap] on [scope]'s dispatcher and calls [deliver] from there. */
    fun request(bitmap: Bitmap) {
        scope.launch {
            val scaled = runCatching { scale(bitmap, maxPx) }.getOrNull()
            deliver(bitmap, scaled)
        }
    }
}

/**
 * Each other player's app label, read once per package. The label is two binder calls into the package manager, and the bridge made them on the main looper at every track
 * change and every command check; now a package is asked once and remembered. A read that fails (the package is not installed yet, or just left) is NOT remembered: the next
 * ask tries again, and meanwhile the caller falls back to the package name as it always did. Not thread-safe by itself: the bridge calls it under its own monitor.
 */
internal class CompanionAppLabelCache(private val read: (String) -> String?) {
    private val labels = HashMap<String, String>()

    fun labelFor(packageName: String): String {
        labels[packageName]?.let { return it }
        val label = read(packageName)
        if (label != null) labels[packageName] = label
        return label ?: packageName
    }

    fun clear() = labels.clear()
}

/**
 * Whether [candidate], a bitmap just read from a session, is the SAME artwork as [held], the one the bridge is already scaling or has scaled for this track. `getMetadata()` is a
 * binder read with no client cache, so every read hands back a NEW Bitmap object: identity by reference never matches, and a publish that waits for a scale would ask for another
 * one forever (the track, and every play or pause after it, never published). The art's own URI names it when the session gives one (a stable string); otherwise, under the same
 * track key, the same width, height and config is the same art. The generation id is not used: it is per instance after an unparcel.
 */
internal fun companionMediaArtworkIsSameArt(held: Bitmap, heldUri: String?, candidate: Bitmap, candidateUri: String?): Boolean {
    if (heldUri != null && candidateUri != null) return heldUri == candidateUri
    return held.width == candidate.width && held.height == candidate.height && held.config == candidate.config
}

/**
 * What the bridge holds for the selected track's artwork: the bitmap it asked to have scaled ([source]), the scaled copy or small original to publish ([value]), and whether the
 * scale is still running ([pending], when the track is not published yet so title and art arrive together). [resolve] is called at every publish with the bitmap just read; a
 * bitmap that is the same artwork ([companionMediaArtworkIsSameArt]) changes nothing, and in particular leaves [source] pointing at the bitmap that was handed to the scaler, so
 * [scaled] still accepts the answer for it. [requestScale] hands a bitmap to the scaler and [needsScaling] says whether it must be. Used under the bridge's monitor.
 */
internal class CompanionArtworkState(
    private val needsScaling: (Bitmap) -> Boolean,
    private val requestScale: (Bitmap) -> Unit,
    private val sameArt: (held: Bitmap, heldUri: String?, candidate: Bitmap, candidateUri: String?) -> Boolean = ::companionMediaArtworkIsSameArt,
) {
    private var trackKey: Any? = null
    private var source: Bitmap? = null
    private var sourceUri: String? = null

    /** The bitmap to publish: the scaled copy, or the original when it was small. Null while the scale is pending, after a failed scale, and when the track has no art. */
    var value: Bitmap? = null
        private set

    /** True while the scaled copy of the source is being made off the main looper. */
    var pending: Boolean = false
        private set

    /** The bitmap [bitmap] (with its art URI [uri], when the session gave one) was read for the track [key]; null [bitmap] is a track with no bitmap art. */
    fun resolve(key: Any, bitmap: Bitmap?, uri: String?) {
        if (trackKey != key) {
            trackKey = key
            clear()
        }
        if (bitmap == null) {
            clear()
            return
        }
        val held = source
        if (held != null && sameArt(held, sourceUri, bitmap, uri)) return
        source = bitmap
        sourceUri = uri
        if (needsScaling(bitmap)) {
            // Not on the caller's looper: the scale is asked for here and the track is published when it comes back.
            value = null
            pending = true
            requestScale(bitmap)
        } else {
            value = bitmap
            pending = false
        }
    }

    /** The scale of [forSource] has landed. True when it is still the art being waited for (the caller publishes with it); false for an answer nobody is waiting for. */
    fun scaled(forSource: Bitmap, scaled: Bitmap?): Boolean {
        if (source !== forSource || !pending) return false
        value = scaled
        pending = false
        return true
    }

    /** Forgets the track and its art (the controllers were cleared). */
    fun reset() {
        trackKey = null
        clear()
    }

    private fun clear() {
        source = null
        sourceUri = null
        value = null
        pending = false
    }
}

/** A process-lifetime scope with a handler that logs loudly and keeps the launcher alive (a failed scale is a missing cover, never a crash). */
private val CompanionArtworkScalerScope: CoroutineScope = CoroutineScope(
    SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error -> Log.e("MimirMediaBridge", "artwork scale failed", error) },
)

/** [bitmap] reduced so that its long edge is [maxPx], keeping its proportions; the bitmap itself when it is already within the bound. */
internal fun scaleCompanionArtwork(bitmap: Bitmap, maxPx: Int): Bitmap? {
    val longest = maxOf(bitmap.width, bitmap.height)
    if (longest <= maxPx) return bitmap
    val scale = maxPx.toFloat() / longest.toFloat()
    return Bitmap.createScaledBitmap(
        bitmap,
        (bitmap.width * scale).toInt().coerceAtLeast(1),
        (bitmap.height * scale).toInt().coerceAtLeast(1),
        true,
    )
}
internal const val CompanionMediaTransientGraceMs = 1_500L

internal enum class CompanionMediaPlaybackState {
    PLAYING,
    PAUSED,
    BUFFERING,
    CONNECTING,
    /** Android's skip callbacks are a live session with a temporarily incomplete snapshot. */
    SKIPPING,
    STOPPED,
    UNAVAILABLE,
}

internal sealed interface CompanionMediaArtwork {
    data class BitmapValue(val bitmap: Bitmap) : CompanionMediaArtwork
    data class LocalUri(val uri: Uri) : CompanionMediaArtwork
}

internal enum class CompanionMediaArtworkLoadState {
    READY,
    LOADING,
    ERROR,
    NO_ART,
}

internal data class CompanionMediaTrack(
    val title: String,
    val artist: String?,
    val appLabel: String,
    val packageName: String,
    val canPrevious: Boolean,
    val canPlayPause: Boolean,
    val canNext: Boolean,
    val sessionIdentity: MediaSession.Token,
    val trackIdentity: String,
    val playbackState: CompanionMediaPlaybackState,
    val artwork: CompanionMediaArtwork? = null,
    val positionMs: Long? = null,
    val durationMs: Long? = null,
    val canSeek: Boolean = false,
    val positionUpdateElapsedRealtimeMs: Long? = null,
    val playbackSpeed: Float? = null,
    /** Optional metadata retained for the compact Activity summary. */
    val album: String? = null,
    /** True while the session snapshot is incomplete; retained fields never imply playback. */
    val transientGap: Boolean = false,
    /** Presentation identity is stable while Android briefly withdraws metadata. */
    val presentationTrackIdentity: String = trackIdentity,
    val artworkLoadState: CompanionMediaArtworkLoadState = when (artwork) {
        is CompanionMediaArtwork.BitmapValue -> CompanionMediaArtworkLoadState.READY
        is CompanionMediaArtwork.LocalUri -> CompanionMediaArtworkLoadState.LOADING
        null -> CompanionMediaArtworkLoadState.NO_ART
    },
    val transitionDirection: CompanionMediaTransitionDirection? = null,
    /**
     * True when [title] is the session's own track title (authored text: a surface draws it in its own case). False when the bridge fell back to its own
     * copy ("Media unavailable") because the session named no title; a retained title is authored only if the one it retains was. The bridge knows
     * which it did, so no surface has to guess it from the string.
     */
    val titleIsAuthored: Boolean = true,
)

internal enum class CompanionMediaTransitionDirection {
    PREVIOUS,
    NEXT,
}

internal sealed interface CompanionMediaUiState {
    data object AccessRequired : CompanionMediaUiState
    data object NoActiveMedia : CompanionMediaUiState
    /** Access is present, but Android did not return a usable controller snapshot. */
    data object SessionUnavailable : CompanionMediaUiState
    data class Active(val track: CompanionMediaTrack) : CompanionMediaUiState
}

internal enum class CompanionMediaAction {
    PREVIOUS,
    PLAY_PAUSE,
    NEXT,
}

internal data class CompanionMediaTimingSnapshot(
    val positionMs: Long,
    val durationMs: Long,
)

internal fun companionMediaSelectionIndex(playing: List<Boolean>, preferredIndex: Int? = null): Int? {
    val preferred = preferredIndex?.takeIf { it in playing.indices }
    // Keep the selected session through a pause/skip gap. A real other playing session wins, and
    // the same rule is reused by currentController so a rendered control cannot drift from dispatch.
    if (preferred != null && playing[preferred]) return preferred
    return playing.indexOfFirst { it }.takeIf { it >= 0 }
        ?: preferred
        ?: playing.indices.firstOrNull()
}

internal fun companionMediaArtworkMayRetainPrevious(
    sameSession: Boolean,
    retentionStartedAtElapsedMs: Long?,
    nowElapsedMs: Long,
    candidateState: CompanionMediaArtworkLoadState,
): Boolean {
    if (!sameSession || retentionStartedAtElapsedMs == null || nowElapsedMs < retentionStartedAtElapsedMs) return false
    if (nowElapsedMs - retentionStartedAtElapsedMs > CompanionMediaTransientGraceMs) return false
    return candidateState == CompanionMediaArtworkLoadState.LOADING ||
        candidateState == CompanionMediaArtworkLoadState.ERROR ||
        candidateState == CompanionMediaArtworkLoadState.NO_ART
}

internal fun companionMediaArtworkCompletionAccepted(
    currentRequestKey: String?,
    currentGeneration: Long,
    completionRequestKey: String,
    completionGeneration: Long,
): Boolean = currentRequestKey == completionRequestKey && currentGeneration == completionGeneration

internal fun companionMediaCommandMatches(expectedIdentity: Any?, currentIdentity: Any): Boolean =
    expectedIdentity == null || expectedIdentity == currentIdentity

internal fun companionMediaTrackMatches(expectedIdentity: String?, currentIdentity: String): Boolean =
    expectedIdentity == null || expectedIdentity == currentIdentity

internal fun companionMediaActionSupported(action: CompanionMediaAction, playing: Boolean, actions: Long): Boolean {
    val required = when (action) {
        CompanionMediaAction.PREVIOUS -> android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS
        CompanionMediaAction.NEXT -> android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT
        CompanionMediaAction.PLAY_PAUSE -> if (playing) {
            android.media.session.PlaybackState.ACTION_PAUSE
        } else {
            android.media.session.PlaybackState.ACTION_PLAY
        }
    }
    return (actions and required) != 0L ||
        (action == CompanionMediaAction.PLAY_PAUSE &&
            (actions and android.media.session.PlaybackState.ACTION_PLAY_PAUSE) != 0L)
}

/** A timeline exists only when Android supplies a finite, internally consistent range. */
internal fun companionMediaTimingSnapshot(positionMs: Long?, durationMs: Long?): CompanionMediaTimingSnapshot? {
    if (positionMs == null || durationMs == null) return null
    if (positionMs < 0L || durationMs <= 0L || positionMs > durationMs) return null
    return CompanionMediaTimingSnapshot(positionMs, durationMs)
}

internal fun companionMediaSeekPosition(
    positionMs: Long,
    durationMs: Long?,
    canSeek: Boolean,
): Long? {
    if (!canSeek || durationMs == null || durationMs <= 0L) return null
    return positionMs.takeIf { it in 0L..durationMs }
}

/**
 * Projects a callback snapshot onto the elapsed realtime clock without inventing a timeline.
 * Invalid metadata, clocks, or speeds preserve the last callback position; only PLAYING advances.
 */
internal fun companionMediaProjectedPosition(
    positionMs: Long?,
    durationMs: Long?,
    playbackState: CompanionMediaPlaybackState,
    positionUpdateElapsedRealtimeMs: Long?,
    playbackSpeed: Float?,
    nowElapsedRealtimeMs: Long,
): Long? {
    val timing = companionMediaTimingSnapshot(positionMs, durationMs) ?: return null
    if (playbackState != CompanionMediaPlaybackState.PLAYING) return timing.positionMs
    val updateTime = positionUpdateElapsedRealtimeMs ?: return timing.positionMs
    val speed = playbackSpeed ?: return timing.positionMs
    if (updateTime <= 0L || nowElapsedRealtimeMs < 0L || nowElapsedRealtimeMs < updateTime) {
        return timing.positionMs
    }
    if (!speed.isFinite() || speed < 0f) return timing.positionMs
    val elapsedMs = nowElapsedRealtimeMs - updateTime
    val remainingMs = timing.durationMs - timing.positionMs
    val projectedAdvanceMs = (elapsedMs.toDouble() * speed.toDouble())
        .coerceIn(0.0, remainingMs.toDouble())
        .toLong()
    return timing.positionMs + projectedAdvanceMs
}

internal fun companionMediaLocalArtworkUri(raw: String?): Uri? {
    val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (!companionMediaArtworkUriIsLocal(text)) return null
    return runCatching { Uri.parse(text) }.getOrNull()
}

/** Scheme gate kept independent from Uri parsing so it is also testable on the JVM shadow. */
internal fun companionMediaArtworkUriIsLocal(raw: String): Boolean {
    val separator = raw.indexOf(':')
    if (separator <= 0) return false
    return raw.substring(0, separator).lowercase() in setOf("content", "file", "android.resource")
}

internal fun companionMediaFormatTime(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds / 60L) % 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/** Callback-driven bridge over Android's active media sessions; it never reads notification text. */
internal object CompanionMediaSessionBridge {
    private val mutableState = MutableStateFlow<CompanionMediaUiState>(CompanionMediaUiState.AccessRequired)
    val state: StateFlow<CompanionMediaUiState> = mutableState.asStateFlow()

    private var appContext: Context? = null
    private var manager: MediaSessionManager? = null
    private var listenerComponent: ComponentName? = null
    private var activeSessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null

    /**
     * QA-09 : the grant landed and the Activity Hub kept showing
     * [CompanionMediaUiState.AccessRequired].
     *
     * Until now the only thing that could move this object off AccessRequired after a grant was
     * [CompanionMediaNotificationListenerService.onListenerConnected] -- Android binding the service.
     * The system did bind it on the recorded run (`NotificationListeners: 0 notification listener service
     * connected`, 12:23:30.690), so either the callback did not reach us or the refresh it triggered did
     * not change what the Hub was reading, and **the run could not tell which, because nothing on this
     * path logged anything at all**. That is the first thing fixed below.
     *
     * The second is the dependency itself. A bound service is Android's decision, taken on Android's
     * schedule; the *fact* the UI states -- "is Mimir in `enabled_notification_listeners`" -- is a
     * Settings.Secure string that changes exactly when the player flips the toggle. Observe the fact.
     * This is the same treatment, for the same reason, that the keyboard rows already get
     * (`MimirAppCoreCoordinator`'s shared IME observer): the grant happens in another app's Activity, so
     * no lifecycle callback of Mimir's is guaranteed to run at the moment it becomes true. The service
     * callback stays -- it is still the faster signal when it works, and both routes land on the same
     * [refresh].
     */
    private var accessObserver: ContentObserver? = null
    private var selected: MediaController? = null
    private val activeControllers = LinkedHashMap<MediaSession.Token, MediaController>()
    private val controllerCallbacks = LinkedHashMap<MediaSession.Token, MediaController.Callback>()
    private data class CoherentSnapshot(val track: CompanionMediaTrack, val atElapsedMs: Long)
    private val coherentSnapshots = LinkedHashMap<MediaSession.Token, CoherentSnapshot>()
    private val metadataGapStartedAt = LinkedHashMap<MediaSession.Token, Long>()
    private val expiredSnapshotGaps = LinkedHashSet<MediaSession.Token>()
    private val metadataGapExpiryCallbacks = LinkedHashMap<MediaSession.Token, Runnable>()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val pendingTransitionDirections = LinkedHashMap<MediaSession.Token, CompanionMediaTransitionDirection>()

    /** The artwork's scale runs off the main looper and republishes the selected player when it lands (see [CompanionArtworkScaler]). */
    private val artworkScaler by lazy { CompanionArtworkScaler(deliver = ::onArtworkScaled) }

    /** The selected track's artwork, its pending scale and when a re-read bitmap is the same art (see [CompanionArtworkState]). */
    private val artwork = CompanionArtworkState(needsScaling = { artworkScaler.needsScaling(it) }, requestScale = { artworkScaler.request(it) })

    /** Each other player's app label, read once per package (see [CompanionAppLabelCache]). */
    private val appLabels = CompanionAppLabelCache { packageName ->
        runCatching {
            appContext?.packageManager?.getApplicationLabel(
                appContext?.packageManager?.getApplicationInfo(packageName, 0)!!,
            )?.toString()
        }.getOrNull()
    }

    @Synchronized
    fun start(context: Context) {
        val application = context.applicationContext
        if (appContext === application && manager != null) {
            listenerComponent = ComponentName(application, CompanionMediaNotificationListenerService::class.java)
            registerAccessObserver(application)
            refresh("start/same-context")
            return
        }
        stop()
        appContext = application
        manager = application.getSystemService(MediaSessionManager::class.java)
        listenerComponent = ComponentName(application, CompanionMediaNotificationListenerService::class.java)
        registerAccessObserver(application)
        refresh("start")
    }

    @Synchronized
    fun onListenerConnected(service: CompanionMediaNotificationListenerService) {
        start(service)
        listenerComponent = ComponentName(service, CompanionMediaNotificationListenerService::class.java)
        registerActiveSessionsListener()
        refresh("listener-connected")
    }

    @Synchronized
    fun onListenerDisconnected() {
        unregisterActiveSessionsListener()
        clearControllers()
        Log.i(CompanionMediaAccessLogTag, "listener-disconnected -> AccessRequired")
        mutableState.value = CompanionMediaUiState.AccessRequired
    }

    /**
     * Watches `enabled_notification_listeners` so a grant made in Android's own Settings is noticed
     * without waiting on a service bind or on any Mimir lifecycle callback. See [accessObserver].
     *
     * Registered against the SAME key [notificationAccessEnabled] reads, so the observer and the read
     * can never disagree about what they are tracking. Idempotent: [start] is called on every
     * Presentation construction and on each listener connect.
     */
    private fun registerAccessObserver(application: Context) {
        if (accessObserver != null) return
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                // The toggle moves in both directions; refresh() re-reads and settles either way.
                refresh("access-observer")
            }
        }
        runCatching {
            application.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(EnabledNotificationListenersKey),
                false,
                observer,
            )
        }.onSuccess { accessObserver = observer }
            .onFailure { Log.w(CompanionMediaAccessLogTag, "could not observe $EnabledNotificationListenersKey", it) }
    }

    private fun unregisterAccessObserver() {
        val observer = accessObserver ?: return
        accessObserver = null
        runCatching { appContext?.contentResolver?.unregisterContentObserver(observer) }
    }

    @Synchronized
    fun stop() {
        unregisterActiveSessionsListener()
        // Before appContext is cleared -- unregistering needs the resolver it was registered on.
        unregisterAccessObserver()
        clearControllers()
        manager = null
        appContext = null
        listenerComponent = null
        appLabels.clear()
        mutableState.value = CompanionMediaUiState.AccessRequired
    }

    /**
     * Same ruling as the Usage Access request (see `usageAccessIntentSpecs`): the bare action opens the
     * device-wide list of every notification listener, so the player had to find Mimir in it. The detail
     * action opens Mimir's own row with its toggle.
     *
     * Resolution is checked before launching, so a ROM without the per-listener screen falls through to
     * the list rather than throwing.
     */
    fun openAccessSettings(context: Context) = openAccessSettings(context) { context.startActivity(it) }

    /**
     * The same surfaces, started from Mimir's resumed Activity when there is one (no NEW_TASK, so the page joins Mimir's task and
     * leaves no Recents card of its own; the application-context start is the fallback). Settings › Music's "Show other players"
     * row uses this: the player is in Mimir's own Settings, not on the second screen.
     */
    fun openAccessSettingsFromHost(context: Context) =
        openAccessSettings(context) { startSettingsSurface(context.applicationContext, it) }

    private fun openAccessSettings(context: Context, start: (Intent) -> Unit) {
        val listener = ComponentName(context, CompanionMediaNotificationListenerService::class.java)
        val packageManager = context.packageManager
        val landed = notificationAccessIntents(listener).firstOrNull { intent ->
            intent.resolveActivity(packageManager) != null && runCatching { start(intent) }.isSuccess
        }
        if (landed == null) {
            Log.w(CompanionMediaAccessLogTag, "no notification-access surface on this device")
        }
    }

    @Synchronized
    fun refresh(reason: String = "session-change") {
        val context = appContext ?: return
        val before = mutableState.value
        if (!notificationAccessEnabled(context)) {
            unregisterActiveSessionsListener()
            clearControllers()
            logAccessTransition(reason, before, CompanionMediaUiState.AccessRequired)
            mutableState.value = CompanionMediaUiState.AccessRequired
            return
        }
        if (before == CompanionMediaUiState.AccessRequired) {
            // The moment QA-09 says was invisible: access is on and this object has just learned it.
            // Which `reason` prints here says whether the service bind or the settings observer got
            // there first -- the whole point of carrying the reason through.
            Log.i(CompanionMediaAccessLogTag, "access granted, leaving AccessRequired reason=$reason")
        }
        registerActiveSessionsListener()
        val sessions = runCatching {
            otherAppSessions(manager?.getActiveSessions(listenerComponent) ?: error("media session manager unavailable"))
        }.getOrElse {
            clearControllers()
            mutableState.value = CompanionMediaUiState.SessionUnavailable
            return
        }
        syncControllerCallbacks(sessions)
        val preferredIndex = selected?.let { current ->
            sessions.indexOfFirst { it.sessionToken == current.sessionToken }.takeIf { it >= 0 }
        }
        val next = companionMediaSelectionIndex(sessions.map(::isPlaying), preferredIndex)?.let(sessions::get)
        if (next == null) {
            clearControllers()
            mutableState.value = CompanionMediaUiState.NoActiveMedia
        } else {
            if (selected?.sessionToken != next.sessionToken) selected = next
            publish(next)
        }
    }

    @Synchronized
    fun dispatch(
        action: CompanionMediaAction,
        expectedSessionIdentity: MediaSession.Token? = null,
        expectedTrackIdentity: String? = null,
    ): Boolean {
        val controller = selected ?: return false
        if (!companionMediaCommandMatches(expectedSessionIdentity, sessionIdentity(controller))) return false
        val current = currentController(controller) ?: return false
        if (!companionMediaTrackMatches(expectedTrackIdentity, trackIdentity(current))) return false
        if (!companionMediaActionSupported(action, isPlaying(current), current.playbackState?.actions ?: 0L)) return false
        return runCatching {
            when (action) {
                CompanionMediaAction.PREVIOUS -> current.transportControls.skipToPrevious()
                CompanionMediaAction.PLAY_PAUSE -> if (isPlaying(current)) {
                    current.transportControls.pause()
                } else {
                    current.transportControls.play()
                }
                CompanionMediaAction.NEXT -> current.transportControls.skipToNext()
            }
            when (action) {
                CompanionMediaAction.PREVIOUS -> {
                    pendingTransitionDirections[sessionIdentity(controller)] = CompanionMediaTransitionDirection.PREVIOUS
                }
                CompanionMediaAction.NEXT -> {
                    pendingTransitionDirections[sessionIdentity(controller)] = CompanionMediaTransitionDirection.NEXT
                }
                CompanionMediaAction.PLAY_PAUSE -> Unit
            }
            true
        }.getOrElse {
            refresh()
            false
        }
    }

    /**
     * Opens the selected other player's app from the music panel's artwork: the session's own activity, else the package's launch
     * intent ([launchOtherPlayer]). The player the panel shows is the one the transport buttons drive, which is [selected]. False
     * when there is no session, no context, or nothing could be started.
     */
    @Synchronized
    fun openSelectedPlayer(sourceView: android.view.View?, bounds: android.graphics.Rect?): Boolean {
        val context = appContext ?: return false
        val controller = selected ?: return false
        val sessionActivity = runCatching { controller.sessionActivity }.getOrNull()
        val launchIntent = runCatching { context.packageManager.getLaunchIntentForPackage(controller.packageName) }.getOrNull()
        return launchOtherPlayer(context, sourceView, bounds, sessionActivity, launchIntent)
    }

    /** Dispatches one bounded command after a seek drag; callbacks remain the source of displayed position. */
    @Synchronized
    fun dispatchSeek(
        positionMs: Long,
        expectedSessionIdentity: MediaSession.Token,
        expectedTrackIdentity: String,
    ): Boolean {
        val controller = selected ?: return false
        if (!companionMediaCommandMatches(expectedSessionIdentity, sessionIdentity(controller))) return false
        val current = currentController(controller) ?: return false
        if (!companionMediaTrackMatches(expectedTrackIdentity, trackIdentity(current))) return false
        val state = current.playbackState ?: return false
        val durationMs = current.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0L }
        val canSeek = (state.actions and android.media.session.PlaybackState.ACTION_SEEK_TO) != 0L
        if (companionMediaTimingSnapshot(state.position, durationMs) == null) return false
        val bounded = companionMediaSeekPosition(positionMs, durationMs, canSeek) ?: return false
        return runCatching {
            current.transportControls.seekTo(bounded)
            true
        }.getOrElse {
            refresh()
            false
        }
    }

    /**
     * Sessions of other apps only. Mimir's own MediaSession (Your music's, see MimirMusicService) is the same package and would otherwise
     * be offered here as another app's player; before it existed there was never an own session, so this keeps what the companion showed.
     */
    private fun otherAppSessions(sessions: List<MediaController>): List<MediaController> {
        val own = appContext?.packageName ?: return sessions
        return sessions.filter { it.packageName != own }
    }

    private fun currentController(controller: MediaController): MediaController? = runCatching {
        val sessions = otherAppSessions(manager?.getActiveSessions(listenerComponent).orEmpty())
        val preferredIndex = sessions.indexOfFirst { it.sessionToken == controller.sessionToken }
            .takeIf { it >= 0 }
        val selectedIndex = companionMediaSelectionIndex(sessions.map(::isPlaying), preferredIndex)
            ?: return@runCatching null
        sessions.getOrNull(selectedIndex)?.takeIf { it.sessionToken == controller.sessionToken }
    }.getOrNull()

    private fun registerActiveSessionsListener() {
        val sessionManager = manager ?: return
        val component = listenerComponent ?: return
        if (activeSessionsListener != null) return
        val listener = MediaSessionManager.OnActiveSessionsChangedListener { refresh() }
        activeSessionsListener = listener
        runCatching { sessionManager.addOnActiveSessionsChangedListener(listener, component) }
            .onFailure { activeSessionsListener = null }
    }

    private fun unregisterActiveSessionsListener() {
        val listener = activeSessionsListener ?: return
        runCatching { manager?.removeOnActiveSessionsChangedListener(listener) }
        activeSessionsListener = null
    }

    private fun syncControllerCallbacks(sessions: List<MediaController>) {
        val current = sessions.associateBy(::sessionIdentity)
        controllerCallbacks.keys.toList().filter { it !in current }.forEach { identity ->
            val callback = controllerCallbacks.remove(identity)
            if (callback != null) activeControllers[identity]?.unregisterCallback(callback)
            activeControllers.remove(identity)
            coherentSnapshots.remove(identity)
            metadataGapStartedAt.remove(identity)
            expiredSnapshotGaps.remove(identity)
            cancelMetadataGapExpiry(identity)
            pendingTransitionDirections.remove(identity)
        }
        sessions.forEach { controller ->
            val identity = sessionIdentity(controller)
            if (identity !in controllerCallbacks) {
                activeControllers[identity] = controller
                val callback = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: android.media.session.PlaybackState?) {
                        if (activeControllers[identity]?.sessionToken == controller.sessionToken) refresh()
                    }

                    override fun onMetadataChanged(metadata: MediaMetadata?) {
                        if (activeControllers[identity]?.sessionToken == controller.sessionToken) refresh()
                    }

                    override fun onSessionDestroyed() {
                        if (activeControllers[identity]?.sessionToken == controller.sessionToken) {
                            activeControllers.remove(identity)
                            coherentSnapshots.remove(identity)
                            metadataGapStartedAt.remove(identity)
                            expiredSnapshotGaps.remove(identity)
                            cancelMetadataGapExpiry(identity)
                            pendingTransitionDirections.remove(identity)
                            if (selected?.sessionToken == controller.sessionToken) selected = null
                            refresh()
                        }
                    }
                }
                controllerCallbacks[identity] = callback
                runCatching { controller.registerCallback(callback) }
                    .onFailure { controllerCallbacks.remove(identity); activeControllers.remove(identity) }
            }
        }
    }

    private fun clearControllers() {
        controllerCallbacks.forEach { (identity, callback) -> activeControllers[identity]?.unregisterCallback(callback) }
        controllerCallbacks.clear()
        activeControllers.clear()
        selected = null
        coherentSnapshots.clear()
        metadataGapStartedAt.clear()
        expiredSnapshotGaps.clear()
        metadataGapExpiryCallbacks.keys.toList().forEach(::cancelMetadataGapExpiry)
        pendingTransitionDirections.clear()
        artwork.reset()
    }

    private fun scheduleMetadataGapExpiry(session: MediaSession.Token, startedAtElapsedMs: Long) {
        metadataGapExpiryCallbacks.remove(session)?.let(mainHandler::removeCallbacks)
        val callback = Runnable {
            synchronized(this) {
                if (metadataGapStartedAt[session] != startedAtElapsedMs) return@synchronized
                metadataGapStartedAt.remove(session)
                metadataGapExpiryCallbacks.remove(session)
                expiredSnapshotGaps += session
                refresh()
            }
        }
        metadataGapExpiryCallbacks[session] = callback
        mainHandler.postDelayed(callback, CompanionMediaTransientGraceMs)
    }

    private fun cancelMetadataGapExpiry(session: MediaSession.Token) {
        metadataGapExpiryCallbacks.remove(session)?.let(mainHandler::removeCallbacks)
    }

    /** The package's app label, from the cache when it has been read before. */
    private fun appLabelFor(packageName: String): String = appLabels.labelFor(packageName)

    /** The scaled artwork has landed (called from the scaling dispatcher): if it is still the selected track's, publish with it. */
    @Synchronized
    private fun onArtworkScaled(source: Bitmap, scaled: Bitmap?) {
        if (!artwork.scaled(source, scaled)) return
        selected?.let(::publish)
    }

    private fun publish(controller: MediaController) {
        val metadata = controller.metadata
        val appLabel = appLabelFor(controller.packageName)
        val metadataTitle = metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim()?.takeIf(String::isNotEmpty)
        val metadataArtist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim()?.takeIf(String::isNotEmpty)
        val metadataAlbum = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)?.trim()?.takeIf(String::isNotEmpty)
        val playbackState = controller.playbackState
        val durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0L }
        val timing = companionMediaTimingSnapshot(playbackState?.position, durationMs)
        val session = sessionIdentity(controller)
        val rawTitle = metadataTitle ?: appLabel
        val rawTrackIdentity = mediaTrackIdentity(controller, rawTitle, metadataArtist)
        val rawArtwork = companionMediaArtwork(metadata, session, rawTrackIdentity)
        // The artwork is being scaled off the main looper: this track is published once it is ready (title and art together), by [onArtworkScaled]. Until then the
        // state shows the previous one.
        if (artwork.pending) return
        val now = SystemClock.elapsedRealtime()
        val metadataGap = metadataTitle == null
        val playbackGap = playbackState?.state == null
        val snapshotGap = metadataGap || playbackGap
        val gapExpired = session in expiredSnapshotGaps
        if (!snapshotGap) expiredSnapshotGaps.remove(session)
        val rawPlaybackState = companionMediaPlaybackState(playbackState?.state)
        val confirmedError = playbackState?.state != null && rawPlaybackState == CompanionMediaPlaybackState.UNAVAILABLE
        val previous = coherentSnapshots[session]
        val coherentPrevious = if (confirmedError) null else previous
        if (confirmedError) {
            coherentSnapshots.remove(session)
            metadataGapStartedAt.remove(session)
            expiredSnapshotGaps.remove(session)
            cancelMetadataGapExpiry(session)
        }
        val gapStartedAt = if (snapshotGap && !gapExpired && !confirmedError) {
            metadataGapStartedAt[session] ?: now.also {
                metadataGapStartedAt[session] = it
                scheduleMetadataGapExpiry(session, it)
            }
        } else {
            metadataGapStartedAt.remove(session)
            cancelMetadataGapExpiry(session)
            null
        }
        val previousIsFresh = previous != null && gapStartedAt != null && now >= gapStartedAt &&
            now - gapStartedAt <= CompanionMediaTransientGraceMs
        val retainMetadata = metadataGap && previousIsFresh
        val title = when {
            retainMetadata -> coherentPrevious!!.track.title
            metadataGap -> "Media unavailable"
            else -> rawTitle
        }
        val artist = metadataArtist ?: coherentPrevious?.track?.artist?.takeIf { retainMetadata }
        val album = metadataAlbum ?: coherentPrevious?.track?.album?.takeIf { retainMetadata }
        val skipDirection = companionMediaTransitionDirection(playbackState?.state)
        if (skipDirection != null) pendingTransitionDirections[session] = skipDirection
        val trackDirection = when {
            skipDirection != null -> skipDirection
            !snapshotGap && coherentPrevious?.track?.trackIdentity != rawTrackIdentity && pendingTransitionDirections[session] != null ->
                pendingTransitionDirections.remove(session)
            else -> null
        }
        // A withdrawn snapshot keeps its player geometry during the bounded grace period without
        // claiming that the retained title implies a valid playing state.
        val transientGap = snapshotGap && !gapExpired && !confirmedError
        val artworkState = when {
            rawArtwork is CompanionMediaArtwork.BitmapValue -> CompanionMediaArtworkLoadState.READY
            rawArtwork is CompanionMediaArtwork.LocalUri -> CompanionMediaArtworkLoadState.LOADING
            metadataGap && previousIsFresh -> CompanionMediaArtworkLoadState.LOADING
            metadataGap -> CompanionMediaArtworkLoadState.NO_ART
            else -> CompanionMediaArtworkLoadState.NO_ART
        }
        val track = CompanionMediaTrack(
            title = title,
            artist = artist,
            appLabel = appLabel,
            packageName = controller.packageName,
            canPrevious = supports(controller, android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS),
            canPlayPause = companionMediaActionSupported(
                CompanionMediaAction.PLAY_PAUSE,
                isPlaying(controller),
                playbackState?.actions ?: 0L,
            ),
            canNext = supports(controller, android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT),
            sessionIdentity = session,
            // Keep command identity authoritative even while display metadata is retained.
            trackIdentity = rawTrackIdentity,
            presentationTrackIdentity = if (metadataGap && previousIsFresh) {
                coherentPrevious!!.track.presentationTrackIdentity
            } else {
                rawTrackIdentity
            },
            playbackState = rawPlaybackState,
            artwork = rawArtwork,
            positionMs = timing?.positionMs,
            durationMs = timing?.durationMs,
            canSeek = timing != null && supports(controller, android.media.session.PlaybackState.ACTION_SEEK_TO),
            positionUpdateElapsedRealtimeMs = playbackState?.lastPositionUpdateTime,
            playbackSpeed = playbackState?.playbackSpeed,
            album = album,
            transientGap = transientGap,
            artworkLoadState = artworkState,
            transitionDirection = trackDirection,
            titleIsAuthored = when {
                retainMetadata -> coherentPrevious!!.track.titleIsAuthored
                metadataGap -> false
                else -> true
            },
        )
        if (!snapshotGap && !confirmedError) coherentSnapshots[session] = CoherentSnapshot(track, now)
        mutableState.value = CompanionMediaUiState.Active(track)
    }

    private fun mediaTrackIdentity(controller: MediaController, title: String, artist: String?): String {
        val metadataId = controller.metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.trim()
            ?.takeIf(String::isNotEmpty)
        return metadataId ?: listOf(controller.packageName, title, artist.orEmpty()).joinToString("\u001f")
    }

    private fun trackIdentity(controller: MediaController): String {
        val metadata = controller.metadata
        val appLabel = appLabelFor(controller.packageName)
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim()?.takeIf(String::isNotEmpty)
            ?: appLabel
        return mediaTrackIdentity(
            controller,
            title,
            metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim()?.takeIf(String::isNotEmpty),
        )
    }

    private fun companionMediaArtwork(
        metadata: MediaMetadata?,
        sessionIdentity: MediaSession.Token,
        trackIdentity: String,
    ): CompanionMediaArtwork? {
        metadata ?: return null
        val key = sessionIdentity to trackIdentity
        val bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        // The art's URI names it across reads: every read of the metadata hands back a NEW Bitmap, so the bitmap itself cannot (see [companionMediaArtworkIsSameArt]).
        val artUriKeys = listOf(
            MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
            MediaMetadata.METADATA_KEY_ART_URI,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
        )
        artwork.resolve(key, bitmap, artUriKeys.asSequence().mapNotNull { metadata.getString(it)?.takeIf(String::isNotBlank) }.firstOrNull())
        if (bitmap != null) return artwork.value?.let(CompanionMediaArtwork::BitmapValue)
        return artUriKeys.asSequence()
            .mapNotNull { metadata.getString(it) }
            .mapNotNull(::companionMediaLocalArtworkUri)
            .firstOrNull()
            ?.let(CompanionMediaArtwork::LocalUri)
    }

    internal fun companionMediaPlaybackState(state: Int?): CompanionMediaPlaybackState = when (state) {
        android.media.session.PlaybackState.STATE_PLAYING -> CompanionMediaPlaybackState.PLAYING
        android.media.session.PlaybackState.STATE_PAUSED -> CompanionMediaPlaybackState.PAUSED
        android.media.session.PlaybackState.STATE_BUFFERING -> CompanionMediaPlaybackState.BUFFERING
        android.media.session.PlaybackState.STATE_CONNECTING -> CompanionMediaPlaybackState.CONNECTING
        android.media.session.PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        android.media.session.PlaybackState.STATE_SKIPPING_TO_NEXT,
        android.media.session.PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
        -> CompanionMediaPlaybackState.SKIPPING
        android.media.session.PlaybackState.STATE_STOPPED -> CompanionMediaPlaybackState.STOPPED
        else -> CompanionMediaPlaybackState.UNAVAILABLE
    }

    private fun companionMediaTransitionDirection(state: Int?): CompanionMediaTransitionDirection? = when (state) {
        android.media.session.PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> CompanionMediaTransitionDirection.PREVIOUS
        android.media.session.PlaybackState.STATE_SKIPPING_TO_NEXT,
        android.media.session.PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
        -> CompanionMediaTransitionDirection.NEXT
        else -> null
    }

    private fun isPlaying(controller: MediaController): Boolean =
        controller.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING

    private fun supports(controller: MediaController, action: Long): Boolean =
        ((controller.playbackState?.actions ?: 0L) and action) != 0L

    private fun sessionIdentity(controller: MediaController): MediaSession.Token = controller.sessionToken

    private fun notificationAccessEnabled(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    private fun logAccessTransition(
        reason: String,
        before: CompanionMediaUiState,
        after: CompanionMediaUiState,
    ) {
        if (before == after) return
        Log.i(CompanionMediaAccessLogTag, "$reason: ${before.logName()} -> ${after.logName()}")
    }

    private fun CompanionMediaUiState.logName(): String = when (this) {
        CompanionMediaUiState.AccessRequired -> "AccessRequired"
        CompanionMediaUiState.NoActiveMedia -> "NoActiveMedia"
        CompanionMediaUiState.SessionUnavailable -> "SessionUnavailable"
        is CompanionMediaUiState.Active -> "Active"
    }
}

/**
 * `Settings.Secure.ENABLED_NOTIFICATION_LISTENERS` is `@hide`, so the key is spelled out. It is the
 * same string `NotificationManagerCompat.getEnabledListenerPackages` parses, and it is stable platform
 * API surface in practice -- but a failure to observe it is caught and logged rather than thrown,
 * because losing the observer must degrade to the old service-callback-only behaviour, never crash the
 * media bridge.
 */
private const val EnabledNotificationListenersKey = "enabled_notification_listeners"

/** One tag for the whole notification-access path, which had none before QA-09. */
private const val CompanionMediaAccessLogTag = "MimirMediaAccess"

/**
 * The notification-access surfaces to try, best first.
 *
 * The second element is the listener's flattened [ComponentName], carried as
 * `Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME` -- an extra, not a `package:` data URI, which is
 * why this cannot share a list with the Usage Access specs even though it is the same ruling applied to
 * the same kind of grant.
 *
 * Ordered, because the per-listener screen is not universal: the full list is second and plain Settings is
 * the floor. `null` on the fallbacks means "no extra", and they must stay unscoped for the same reason the
 * Usage Access fallbacks do -- a scoped fallback is the first intent again, so a ROM lacking that screen
 * would fail three times instead of falling back once.
 *
 * Kept as data rather than intents so it can be asserted without an Android runtime.
 */
internal fun notificationAccessIntentSpecs(listener: String): List<Pair<String, String?>> = listOf(
    Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS to listener,
    Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS to null,
    Settings.ACTION_SETTINGS to null,
)

/** [notificationAccessIntentSpecs] as launchable intents. */
internal fun notificationAccessIntents(listener: ComponentName): List<Intent> =
    notificationAccessIntentSpecs(listener.flattenToString()).map { (action, component) ->
        Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply {
            component?.let { putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, it) }
        }
    }

internal class CompanionMediaNotificationListenerService : android.service.notification.NotificationListenerService() {
    override fun onListenerConnected() = CompanionMediaSessionBridge.onListenerConnected(this)
    override fun onListenerDisconnected() = CompanionMediaSessionBridge.onListenerDisconnected()
}
