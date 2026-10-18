package com.gruning.mimir.rewrite.core.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What keeps Your music playing with Mimir in the background: a Media3 [MediaSessionService] of foreground type
 * mediaPlayback that wraps the ExoPlayer Your music plays on in its media session (Bluetooth and media keys, the system's media
 * controls) and its media notification (Media3's default provider: title, previous, play/pause, next).
 *
 * **The player is Your music's alone, and the process's.** The theme beds stay on the platform engine ([MimirFrontendMusic]), so this
 * session and this notification only ever reflect Your music. [YourMusicPlayback] builds the player on the main looper and the
 * frontend engine drives it; the engine decides what plays and when. The service owns only the session around it, so a service that
 * is refused, killed or stopped never takes the player with it.
 *
 * **Commands go through the facade, not the player.** The session's player is a [ForwardingPlayer] whose play, pause, next and
 * previous are [MimirMusicControl]'s, so a media key or a notification button obeys the same pause rules, the same focus request
 * and the same "a deliberate play over a hold takes the music back" as the panel. The player takes no audio focus of its own
 * (`handleAudioFocus = false`): [MimirMusicControl] asks for it and gives it up.
 *
 * Started by [MimirMusicControl] while Your music is active, which happens with Mimir in front (Android refuses a foreground-service
 * start from the background; a refused start is logged and retried when Mimir next comes to the front). Stopped when Your music is
 * not the source any more. Notification permission is not required to run: Media3's notification carries the session token, which
 * is exempt from POST_NOTIFICATIONS.
 */
@OptIn(UnstableApi::class)
class MimirMusicService : MediaSessionService() {
    private val failureHandler = CoroutineExceptionHandler { context, throwable ->
        Log.e(Tag, "music service work failed in $context", throwable)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + failureHandler)
    private var session: MediaSession? = null

    /** Main thread only: set in [onDestroy] so nothing re-asserts the foreground state of a dying service. */
    private var destroyed = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // The first notification must exist before the 5 s foreground deadline, whatever the player says yet; Media3 replaces it
        // (same id) once its session is up.
        startForegroundCompat(placeholderNotification())
        _foreground.value = true
        setListener(
            object : Listener {
                override fun onForegroundServiceStartNotAllowedException() {
                    Log.e(Tag, "Android refused the foreground start; Your music keeps its player, the next start from Mimir retries")
                }
            },
        )
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setNotificationId(NotificationId)
                .setChannelId(ChannelId)
                .build(),
        )
        // The player is the process's (YourMusicPlayback builds it, with the playlist the engine chose already loaded), so the first
        // notification already has a track to show; this service only gives it a session.
        val exo = YourMusicPlayback.playerFor(this)
        val mediaSession = MediaSession.Builder(this, FacadePlayer(exo))
            .setId(SessionId)
            .apply { launchIntent()?.let { setSessionActivity(it) } }
            .build()
        session = mediaSession
        // If float output's AudioTrack will not start the player is built again (YourMusicPlayback.rebuildWithoutFloat): the session
        // moves onto the new one, on the main thread, where the player was built.
        YourMusicPlayback.onPlayerReplaced = { replaced -> session?.player = FacadePlayer(replaced) }
        addSession(mediaSession)
        // Launched after startForeground, and a StateFlow replays its current value: whenever Your music is not the source, the service
        // stops itself here, and only ever after it is in the foreground (a stop from outside could land before onCreate).
        scope.launch {
            MimirMusicControl.yourMusicActive.collect { active ->
                if (!active) {
                    Log.i(Tag, "Your music is no longer the source; the service stops")
                    stopSelf()
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Every startForegroundService owes a startForeground, including one that finds this service already running (and a media
        // button, which Media3 delivers the same way): hand the system the notification that is up.
        if (!destroyed) {
            runCatching { startForegroundCompat(currentNotification()) }
                .onFailure { Log.w(Tag, "could not re-assert the foreground state", it) }
        }
        return START_NOT_STICKY
    }

    /**
     * Your music's service is in the foreground for as long as it lives, as the platform-player service was: Media3's own rule
     * (foreground while playing, plus ten minutes after a pause) would let Android demote it behind a game, and a focus request
     * outside Mimir needs the service in the foreground ([foreground]).
     */
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        super.onUpdateNotification(session, true)
    }

    // The default stops the service when nothing is playing, and a swiped-away Mimir must not take Your music's controls with it:
    // it plays everywhere, and the service already stops itself when Your music is no longer the source.
    override fun onTaskRemoved(rootIntent: Intent?) = Unit

    override fun onDestroy() {
        destroyed = true
        _foreground.value = false
        scope.cancel()
        // The session goes, the player stays: it is the process's, so the track keeps its place and a refused restart leaves Your music
        // playing inside Mimir.
        YourMusicPlayback.onPlayerReplaced = null
        session?.let { it.release() }
        session = null
        super.onDestroy()
    }

    /** The player the session sees: the real one's state and tracks, with the commands routed through [MimirMusicControl]. */
    private class FacadePlayer(player: Player) : ForwardingPlayer(player) {
        override fun play() = playIfPaused()

        override fun pause() = pauseIfPlaying()

        override fun setPlayWhenReady(playWhenReady: Boolean) = if (playWhenReady) playIfPaused() else pauseIfPlaying()

        override fun seekToNext() = MimirMusicControl.next(yourMusicOnly = true)

        override fun seekToNextMediaItem() = MimirMusicControl.next(yourMusicOnly = true)

        override fun seekToPrevious() = MimirMusicControl.previous(yourMusicOnly = true)

        override fun seekToPreviousMediaItem() = MimirMusicControl.previous(yourMusicOnly = true)

        // A media key, a controller or the notification's swipe can say stop: for Your music that is a pause through the facade, not
        // the real player going idle under a facade that still reports Playing.
        override fun stop() = pauseIfPlaying()

        // Previous and next are always offered, as they were: a folder of one track restarts on next, and the panel says so. What would
        // change the playlist, the modes, the player gain or its lifecycle behind the facade's back is not offered: the engine owns those.
        override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
            .addAll(
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            )
            .removeAll(
                Player.COMMAND_SET_MEDIA_ITEM,
                Player.COMMAND_CHANGE_MEDIA_ITEMS,
                Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                Player.COMMAND_SET_REPEAT_MODE,
                Player.COMMAND_SET_SHUFFLE_MODE,
                Player.COMMAND_SET_SPEED_AND_PITCH,
                Player.COMMAND_SET_VOLUME,
                Player.COMMAND_PREPARE,
                Player.COMMAND_RELEASE,
            )
            .build()

        override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

        private fun playIfPaused() {
            if (MimirMusicControl.state.value.yours?.isPlaying != true) MimirMusicControl.playPause(yourMusicOnly = true)
        }

        private fun pauseIfPlaying() {
            if (MimirMusicControl.state.value.yours?.isPlaying == true) MimirMusicControl.playPause(yourMusicOnly = true)
        }
    }

    private fun launchIntent(): PendingIntent? =
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

    private fun placeholderNotification(): Notification =
        Notification.Builder(this, ChannelId)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(DefaultTitle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .apply { launchIntent()?.let { setContentIntent(it) } }
            .build()

    /** The notification Media3 has posted under our id, or the placeholder before it has posted one. */
    private fun currentNotification(): Notification =
        runCatching {
            getSystemService(NotificationManager::class.java).activeNotifications.firstOrNull { it.id == NotificationId }?.notification
        }.getOrNull() ?: placeholderNotification()

    private fun startForegroundCompat(notification: Notification) {
        startForeground(NotificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(ChannelId, "Music on this device", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Controls for the music you chose, while it plays outside Mimir."
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val Tag = "MimirMusicService"
        private const val SessionId = "MimirMusic"
        private const val ChannelId = "mimir_your_music"
        private const val NotificationId = 7101
        private const val DefaultTitle = "Music on this device"

        private val _foreground = MutableStateFlow(false)

        /** True from startForeground to onDestroy: the state in which Android 15 lets Mimir take audio focus from outside its UI. */
        internal val foreground: StateFlow<Boolean> = _foreground.asStateFlow()
    }
}
