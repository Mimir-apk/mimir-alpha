package com.gruning.mimir.rewrite

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.gruning.mimir.rewrite.core.audio.AudioFocusState
import com.gruning.mimir.rewrite.core.audio.MimirAudioFocus
import com.gruning.mimir.rewrite.core.audio.MimirMusicControl
import com.gruning.mimir.rewrite.core.audio.OtherPlayerSnapshot
import com.gruning.mimir.rewrite.core.audio.OtherPlayerSource
import com.gruning.mimir.rewrite.core.companionnative.AndroidExternalForegroundMonitor
import com.gruning.mimir.rewrite.core.launch.EmulatorSeedRegistry
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

/**
 * The app's half of "Your music plays everywhere": what core-audio cannot know.
 *
 *  - **A game is in front** (focus loss does not pause Your music then): Mimir's own launch armed ([MimirAudioFocus] Suspended), or the
 *    package UsageStats says is in front right now is a known emulator/game package (read on demand at each focus change, because the
 *    foreground monitor only polls while the companion has something to observe, so it cannot see an emulator opened from the launcher;
 *    its edges still feed [MusicGameFrontTracker]; this needs Usage Access) is the app's EVIDENCE. A live Mimir game session is the other
 *    half and means only that a game exists: the control counts it as in front only while Mimir is not (a game parked behind Mimir, by HOME
 *    or by the switch button, is not on screen: Main's resume lets go of the launch's Suspended for either park, and Main stopping under the
 *    game, fronted or covered, arms it again). The way back to a parked game is evidence too, from [MusicGameHandBack.begin] to Main pausing, for HOME's
 *    Resume and the Bottom's Return alike. The control composes them ([com.gruning.mimir.rewrite.core.audio.MusicPausePolicy.gameInFront]).
 *  - **Another app's player**, offered only through the companion's media-session bridge and its notification-access grant.
 *  - **The one notification ask**, when Your music first plays outside Mimir.
 */
internal object YourMusicEverywhere {
    private const val Tag = "MimirYourMusic"
    private val failureHandler = CoroutineExceptionHandler { context, throwable ->
        Log.e(Tag, "your-music wiring failed in $context", throwable)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + failureHandler)
    private val gamePackages: Set<String> by lazy { EmulatorSeedRegistry.allSeedPackages().toSet() }
    private val tracker = MusicGameFrontTracker { it in gamePackages }
    private fun isGamePackage(packageName: String?): Boolean = packageName != null && packageName in gamePackages
    private var installed = false
    private var askedThisProcess = false

    /** Idempotent across Activity recreation. [monitor] is the process-wide one (a second instance would double its polling). */
    fun install(context: Context, monitor: AndroidExternalForegroundMonitor) {
        synchronized(this) {
            if (installed) return
            installed = true
        }
        val app = context.applicationContext
        tracker.onMimirForegrounded(System.currentTimeMillis())
        // The control composes the three with its own word on whether Main is resumed: a live session alone says a game exists, and one parked behind Mimir is
        // not on screen. The cheap evidence and the costly foreground read are apart so the control asks for the second only when it must, once per pass.
        MimirMusicControl.setGameInFrontProbe(
            MimirMusicControl.GameFrontProbe(
                sessionLive = { musicSessionGameLive(MimirSessionAuthority.state.value) },
                onScreen = {
                    val snapshot = MimirSessionAuthority.state.value
                    musicBottomGameOnScreen(snapshot, bottomParked = snapshot.gameSession?.let { MainGameParkingState.currentFor(it) } != null) ||
                        MusicGameHandBack.active() ||
                        tracker.gameInFront(sessionGameLive = false, launchArmed = MimirAudioFocus.state.value is AudioFocusState.Suspended)
                },
                foreground = { isGamePackage(monitor.foregroundPackageNow()) },
            ),
        )
        // Every change of a parking lease (a HOME park, a Resume, a release, the parked Bottom game's tab tapped) may change what is on screen: the control reconsiders.
        MainGameParkingState.leaseObserver = { MimirMusicControl.onGameFrontMayHaveChanged() }
        // A game Mimir launched may still run behind a restarted process: the elapsed store hands over the record an earlier process left open.
        HomeExternalSessionElapsedStore.orphanObserver = { session -> KeptGameRestartSeed.onOrphanRecord(app, session) }
        scope.launch {
            combine(
                MimirSessionAuthority.state.map(::musicSessionGameLive).distinctUntilChanged(),
                MimirAudioFocus.state,
            ) { live, focus -> live to focus }
                .distinctUntilChanged()
                .collect { MimirMusicControl.onGameFrontMayHaveChanged() }
        }
        scope.launch {
            monitor.foreignForegroundObserved.collect { observation ->
                tracker.onForeign(observation.packageName, observation.observedAtMs)
                MimirMusicControl.onGameFrontMayHaveChanged()
            }
        }
        CompanionMediaSessionBridge.start(app)
        MimirMusicControl.setOtherPlayerSource(BridgeOtherPlayerSource(scope))
        scope.launch {
            MimirMusicControl.yourMusicActive.collect { active ->
                if (active && !askedThisProcess) {
                    askedThisProcess = true
                    // O13: at most once in the app's lifetime (the ledger), and only over Home on a free Main; a decline changes nothing.
                    runCatching { MimirNotificationPermissionAsk.onFeatureTurnedOn(app, NotificationAskFeature.YOUR_MUSIC) }
                        .onFailure { Log.w(NotificationAskLogTag, "your-music ask failed", it) }
                }
            }
        }
    }

    /** Main's onResume: Mimir is on screen, a hand-back to a parked game (if one was under way) is over, and the music control hears it. */
    fun onMainResumed() {
        MusicGameHandBack.end()
        MimirMusicControl.onMainResumed(true)
    }

    /** Main's onPause: Mimir is no longer on screen. This comes BEFORE the game that is coming over it asks for audio focus, which the started edge does not. */
    fun onMainPaused() = MimirMusicControl.onMainResumed(false)

    /** Mimir is in front again: no foreign app is. Called before the music control re-reads the game state. */
    fun onMimirForegrounded() {
        tracker.onMimirForegrounded(System.currentTimeMillis())
        // The notification-access grant is made on another screen: read it again on the way back, so the panel's foot line and the other
        // player follow it at once (the bridge also watches the setting, and this covers a ROM whose change never arrives).
        runCatching { CompanionMediaSessionBridge.refresh("mimir-foregrounded") }
            .onFailure { Log.w(Tag, "could not re-read the media-session grant", it) }
    }
}

/** A Mimir game session the music treats as "a game is in front": launched, on screen or handed to the second screen; not preparing or returning. */
internal fun musicSessionGameLive(snapshot: MimirSessionSnapshot): Boolean =
    snapshot.gameSession?.phase in MusicLiveGamePhases

/**
 * A live game on the second screen is on screen while Mimir is on the first, so it is evidence however the launch's Suspended stands: any resume of
 * Main that holds no Main lease puts focus back to Foreground, and a Bottom game holds none. HOME parking it (a Bottom lease, [bottomParked]) takes it
 * off the screen again. A Main game is read by the control (a live session with Mimir off the front), not here.
 */
internal fun musicBottomGameOnScreen(snapshot: MimirSessionSnapshot, bottomParked: Boolean): Boolean =
    musicSessionGameLive(snapshot) && snapshot.gameSession?.placement == MimirGamePlacement.BOTTOM && !bottomParked

/**
 * Whether Main stopping arms the audio gate as at a launch, because the parked game is in front. True when the stop cleared the restoring mark
 * ([restoringCleared]: a Return or a HOME Resume fronted the task), and also while a Main [lease] still stands: Android's Recents fronts the game with
 * no mark. With a parked game and Main covered, Mimir treats the game as in front, so the Bottom's UI sounds
 * stay quiet until Main resumes. Every resume of Main lets go of it. A Bottom lease is no Main game covering Main, and a stop with no lease at all
 * arms nothing: the screen going off or a launcher app opening over Mimir, while no Main game is parked. With a Main game parked, those stops are
 * covers like any other and arm the gate, whatever took Main off the screen.
 */
internal fun mainStopArmsAudioGate(restoringCleared: Boolean, lease: MainGameParkingLease?): Boolean =
    restoringCleared || (lease != null && !lease.bottom)

/** How long a hand-back to a parked game counts as the game being on screen if Main never pauses (the fronting was refused, or the task was gone). */
internal const val MusicHandBackWindowMs = MainGameParkingResumeTimeoutMs

/** True while [nowMs] is inside a hand-back window that ends at [untilMs]. */
internal fun musicHandBackActive(untilMs: Long, nowMs: Long): Boolean = nowMs < untilMs

/**
 * The moment between Resume (or the Bottom tab's reopen) beginning to front a parked game and Main pausing under it. A parked game's session says only that a
 * game exists, and Main is still resumed until the game's window is up, so without this the game's own audio focus request would arrive while only
 * UsageStats could say "game". Began by the resume path BEFORE it fronts the task; ended by a refused fronting, by Main resuming, or by the window.
 */
internal object MusicGameHandBack {
    @Volatile
    private var untilMs = 0L

    fun begin(nowMs: Long = SystemClock.elapsedRealtime()) {
        untilMs = nowMs + MusicHandBackWindowMs
    }

    fun end() {
        untilMs = 0L
    }

    fun active(nowMs: Long = SystemClock.elapsedRealtime()): Boolean = musicHandBackActive(untilMs, nowMs)

    /**
     * The hand-back begins and the control hears it, so the music lets go of audio focus before the game's own onResume asks for it. The flag is
     * set first, or the pass the control posts would read the old answer. The one beginning for every way back to a parked game: HOME's Resume
     * ([homeResumePorts]) and the Bottom's Return to a game parked behind the switch button ([CompanionDisplayController]). Best effort, any thread.
     */
    fun beginAndWake() {
        begin()
        MimirMusicControl.onGameFrontMayHaveChanged()
    }

    /** The fronting was refused or the lease went: the game is not coming up, so the hand-back is over and the control reconsiders. */
    fun endAndWake() {
        end()
        MimirMusicControl.onGameFrontMayHaveChanged()
    }
}

private val MusicLiveGamePhases = setOf(
    MimirSessionPhase.DISPATCHED,
    MimirSessionPhase.FOREGROUND_PROVEN,
    MimirSessionPhase.YIELDED_TO_SECONDARY_TASK,
)

/**
 * Whether a game is in front, from the foreground monitor's foreign-package edges. A package observed at or before Mimir last came to the
 * front is history (the monitor's flow replays its last edge), and Mimir coming to the front clears it.
 */
internal class MusicGameFrontTracker(private val isGamePackage: (String) -> Boolean) {
    @Volatile
    private var foreignPackage: String? = null

    @Volatile
    private var mimirFrontAtMs = 0L

    fun onMimirForegrounded(atMs: Long) {
        mimirFrontAtMs = atMs
        foreignPackage = null
    }

    fun onForeign(packageName: String, observedAtMs: Long) {
        if (observedAtMs <= mimirFrontAtMs) return
        foreignPackage = packageName
    }

    fun gameInFront(sessionGameLive: Boolean, launchArmed: Boolean): Boolean =
        sessionGameLive || launchArmed || foreignPackage?.let(isGamePackage) == true
}

/**
 * What a session's playback state means to the music facade: true while it plays (BUFFERING, CONNECTING and SKIPPING are playing: a
 * player that is loading its next piece has not stopped, and Your music must not take its audio at that moment), false while it is
 * PAUSED, and null for STOPPED and UNAVAILABLE (the session is not offered; the resume rule reads that as stopped).
 */
internal fun otherPlayerPlayingFor(state: CompanionMediaPlaybackState): Boolean? = when (state) {
    CompanionMediaPlaybackState.PLAYING,
    CompanionMediaPlaybackState.BUFFERING,
    CompanionMediaPlaybackState.CONNECTING,
    CompanionMediaPlaybackState.SKIPPING,
    -> true
    CompanionMediaPlaybackState.PAUSED -> false
    CompanionMediaPlaybackState.STOPPED,
    CompanionMediaPlaybackState.UNAVAILABLE,
    -> null
}

/** The companion bridge's state as the music facade's other player: only an active session that is playing or paused, never ours. */
internal fun otherPlayerSnapshotFor(state: CompanionMediaUiState): OtherPlayerSnapshot? {
    val track = (state as? CompanionMediaUiState.Active)?.track ?: return null
    val playing = otherPlayerPlayingFor(track.playbackState) ?: return null
    return OtherPlayerSnapshot(
        title = track.title,
        artist = track.artist,
        appLabel = track.appLabel,
        artwork = (track.artwork as? CompanionMediaArtwork.BitmapValue)?.bitmap,
        isPlaying = playing,
        canPrevious = track.canPrevious,
        canNext = track.canNext,
        canPlayPause = track.canPlayPause,
        // A named art still being read is not "no art": the panel holds the fallback back until it has finished.
        artworkPending = track.artworkLoadState == CompanionMediaArtworkLoadState.LOADING &&
            (track.artwork as? CompanionMediaArtwork.BitmapValue) == null,
        // A named art that failed to load is wired art that is gone: it follows the pending path (R8), never the fallback glyph.
        artworkFailed = track.artworkLoadState == CompanionMediaArtworkLoadState.ERROR &&
            (track.artwork as? CompanionMediaArtwork.BitmapValue) == null,
        titleIsAuthored = track.titleIsAuthored,
        packageName = track.packageName.takeIf { it.isNotBlank() },
    )
}

/** The notification-access grant as the bridge reports it: every state but AccessRequired means the sessions are being read. */
internal fun sessionAccessGrantedFor(state: CompanionMediaUiState): Boolean = state !is CompanionMediaUiState.AccessRequired

/** [OtherPlayerSource] over [CompanionMediaSessionBridge]: transport controls only, and nothing without the notification-access grant. */
private class BridgeOtherPlayerSource(scope: CoroutineScope) : OtherPlayerSource {
    override val player: StateFlow<OtherPlayerSnapshot?> =
        CompanionMediaSessionBridge.state.map(::otherPlayerSnapshotFor)
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, null)

    override val sessionAccessGranted: StateFlow<Boolean> =
        CompanionMediaSessionBridge.state.map(::sessionAccessGrantedFor)
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, false)

    override fun playPause() = dispatch(CompanionMediaAction.PLAY_PAUSE)

    override fun next() = dispatch(CompanionMediaAction.NEXT)

    override fun previous() = dispatch(CompanionMediaAction.PREVIOUS)

    private fun dispatch(action: CompanionMediaAction) {
        val track = (CompanionMediaSessionBridge.state.value as? CompanionMediaUiState.Active)?.track ?: return
        CompanionMediaSessionBridge.dispatch(action, track.sessionIdentity, track.trackIdentity)
    }
}
