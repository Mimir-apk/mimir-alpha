package com.gruning.mimir.rewrite

import android.os.SystemClock
import com.gruning.mimir.rewrite.feature.companion.CompanionSceneTransition
import com.gruning.mimir.rewrite.feature.companion.LaunchedEffectWhileLive
import com.gruning.mimir.rewrite.feature.companion.CompanionAppIconArt
import com.gruning.mimir.rewrite.feature.companion.collectAsStateWhileLive
import android.media.session.MediaSession
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import com.gruning.mimir.rewrite.core.ui.LocalMimirMotionLive
import androidx.compose.runtime.snapshots.Snapshot
import com.gruning.mimir.rewrite.core.ui.MimirGestureWatch
import com.gruning.mimir.rewrite.core.ui.mimirGestureWatch
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.gruning.mimir.rewrite.core.input.mimirActivatable
import com.gruning.mimir.rewrite.core.model.CompanionAppEntry
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.ui.BottomSurface
import com.gruning.mimir.rewrite.core.ui.MimirAchievementCardState
import com.gruning.mimir.rewrite.core.ui.MimirAchievementCopy
import com.gruning.mimir.rewrite.core.ui.BottomUiArtReceipt
import com.gruning.mimir.rewrite.core.ui.BottomUiSurfaceReceipt
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.mimirEtchedChipSurface
import com.gruning.mimir.rewrite.core.ui.rememberBottomUiControl
import com.gruning.mimir.rewrite.core.ui.rememberMimirArtWired
import com.gruning.mimir.rewrite.core.ui.MediaArtFallbackGlyph
import com.gruning.mimir.rewrite.core.ui.MimirBoxArt
import com.gruning.mimir.rewrite.core.ui.mimirPressResponse
import com.gruning.mimir.rewrite.core.ui.MimirFlatLogoArt
import com.gruning.mimir.rewrite.core.ui.MimirFlatLogoArtDefaults
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirLiquidGlassPanel
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.mimirReducedMotion
import com.gruning.mimir.rewrite.core.ui.MimirSwapPageGate
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.BottomUiTransitionProbe
import com.gruning.mimir.rewrite.core.ui.bottomUiReducedMotion
import com.gruning.mimir.rewrite.core.ui.mimirSwapFadeAlpha
import com.gruning.mimir.rewrite.core.ui.mimirSwapTransform
import kotlinx.coroutines.delay

private const val CompanionMediaArtworkDecodePx = 512

private data class CompanionResolvedMediaArtwork(
    val painter: Painter?,
    val state: CompanionMediaArtworkLoadState,
    val visualTrack: CompanionMediaTrack?,
    val pendingVisualCommit: Boolean,
    /** Reads the expiry tick so Compose observes the bounded retention timer. */
    val revision: Int,
)

/**
 * Keeps one decoded image for the centered card and the full activity backdrop. A new media
 * snapshot may arrive before its URI is decoded; the previous coherent image is retained for the
 * same session for the bridge's bounded grace period, then the current snapshot is shown.
 */
internal class CompanionMediaArtworkMemory {
    var requestKey: String? = null
    var generation: Long = 0L
    var retentionStartedAtElapsedMs: Long? = null
    var pendingSession: MediaSession.Token? = null
    var pendingPresentationIdentity: String? = null
    var displayedRequestKey: String? = null
    var displayedPainter: Painter? = null
    var displayedTrack: CompanionMediaTrack? = null
    var displayedState: CompanionMediaArtworkLoadState = CompanionMediaArtworkLoadState.NO_ART
    val settledPainters = LinkedHashMap<String, Painter>()

    fun beginRequest(key: String?, track: CompanionMediaTrack?, now: Long) {
        if (requestKey == key) {
            if (track != null && displayedRequestKey == key && displayedState == CompanionMediaArtworkLoadState.READY) {
                displayedTrack = track
            }
            return
        }
        val samePresentation = track != null && pendingSession == track.sessionIdentity &&
            pendingPresentationIdentity == track.presentationTrackIdentity
        requestKey = key
        generation += 1L
        if (!samePresentation) retentionStartedAtElapsedMs = now
        if (key == null || track == null) {
            pendingSession = null
            pendingPresentationIdentity = null
            displayedRequestKey = null
            displayedPainter = null
            displayedTrack = null
            displayedState = CompanionMediaArtworkLoadState.NO_ART
            return
        }
        pendingSession = track.sessionIdentity
        pendingPresentationIdentity = track.presentationTrackIdentity
        val settled = settledPainters[key]
        if (settled != null) {
            displayedRequestKey = key
            displayedPainter = settled
            displayedTrack = track
            displayedState = CompanionMediaArtworkLoadState.READY
        } else if (displayedTrack?.sessionIdentity != track.sessionIdentity) {
            displayedRequestKey = null
            displayedPainter = null
            displayedTrack = null
            displayedState = CompanionMediaArtworkLoadState.NO_ART
        }
        while (settledPainters.size > 4) settledPainters.remove(settledPainters.keys.first())
    }

    fun settle(key: String, generation: Long, painter: Painter, track: CompanionMediaTrack) {
        if (!companionMediaArtworkCompletionAccepted(requestKey, this.generation, key, generation)) return
        settledPainters[key] = painter
        displayedRequestKey = key
        displayedPainter = painter
        displayedTrack = track
        displayedState = CompanionMediaArtworkLoadState.READY
        while (settledPainters.size > 4) settledPainters.remove(settledPainters.keys.first())
    }

    /**
     * R8a: [key]'s artwork file is GONE, so it is no art. A painter remembered as settled for it (the same track returning after its
     * file was deleted) must stop showing: drop it from the settled set and, when it is the one on display, from the display too, so
     * nothing reads it back as READY once the retention grace has passed. The request itself stays current; only what was
     * remembered of its art goes.
     */
    fun forget(key: String) {
        settledPainters.remove(key)
        if (displayedRequestKey == key) {
            displayedRequestKey = null
            displayedPainter = null
            displayedTrack = null
            displayedState = CompanionMediaArtworkLoadState.NO_ART
        }
    }
}

private fun companionMediaArtworkRequestKey(track: CompanionMediaTrack?): String? = track?.let { current ->
    val source = when (val artwork = current.artwork) {
        is CompanionMediaArtwork.BitmapValue -> "bitmap:${System.identityHashCode(artwork.bitmap)}"
        is CompanionMediaArtwork.LocalUri -> "uri:${artwork.uri}"
        null -> "none"
    }
    "${current.sessionIdentity}|${current.trackIdentity}|$source"
}

/**
 * The load state the Activity media art resolves to. A deleted local `file:` artwork ([localFileGone], R8a) is NO_ART, the normal
 * plate and glyph, even if the painter reported an error before the off-main stat landed; an error for a file that is there is ERROR,
 * which draws nothing (R8).
 */
internal fun companionMediaArtworkCandidateState(
    hasDirectPainter: Boolean,
    localFileGone: Boolean,
    asyncSuccess: Boolean,
    asyncError: Boolean,
    trackState: CompanionMediaArtworkLoadState?,
): CompanionMediaArtworkLoadState = when {
    hasDirectPainter -> CompanionMediaArtworkLoadState.READY
    localFileGone -> CompanionMediaArtworkLoadState.NO_ART
    asyncSuccess -> CompanionMediaArtworkLoadState.READY
    asyncError -> CompanionMediaArtworkLoadState.ERROR
    trackState == CompanionMediaArtworkLoadState.LOADING -> CompanionMediaArtworkLoadState.LOADING
    else -> trackState ?: CompanionMediaArtworkLoadState.NO_ART
}

@Composable
private fun rememberCompanionMediaArtwork(track: CompanionMediaTrack?): CompanionResolvedMediaArtwork {
    val context = LocalContext.current
    val memory = remember { CompanionMediaArtworkMemory() }
    var revision by remember { mutableStateOf(0) }
    val requestKey = companionMediaArtworkRequestKey(track)
    val artwork = track?.artwork
    // R8a: a local `file:` artwork whose file is gone is NO art (the plate and glyph), not a failed load, so the painter is not even
    // started for it. The stat is off-main ([rememberMimirArtWired]); while it is pending the artwork counts as wired.
    val localUri = (artwork as? CompanionMediaArtwork.LocalUri)?.uri
    val localArtWired = rememberMimirArtWired(localUri)
    val localFileGone = localUri != null && !localArtWired
    val asyncPainter = if (artwork is CompanionMediaArtwork.LocalUri && requestKey != null && !localFileGone) {
        key(requestKey ?: "") {
            rememberAsyncImagePainter(
                model = ImageRequest.Builder(context)
                    .data(artwork.uri)
                    .size(CompanionMediaArtworkDecodePx)
                    .crossfade(false)
                    .build(),
            )
        }
    } else {
        null
    }
    val directPainter = if (artwork is CompanionMediaArtwork.BitmapValue && requestKey != null) {
        remember(requestKey) { BitmapPainter(artwork.bitmap.asImageBitmap()) }
    } else {
        null
    }
    val now = SystemClock.elapsedRealtime()
    memory.beginRequest(requestKey, track, now)
    // R8a: a deleted artwork file is no art, so a painter remembered as settled for THIS request must not outlive that verdict
    // (the same track returning after its file went), or it would read back as READY once the retention grace had passed.
    if (localFileGone && requestKey != null) memory.forget(requestKey)
    if (directPainter != null && track != null && memory.displayedRequestKey != requestKey) {
        requestKey?.let { key -> memory.settle(key, memory.generation, directPainter, track) }
    }
    val asyncState = asyncPainter?.state
    if (asyncPainter != null && track != null && requestKey != null) {
        val generation = memory.generation
        LaunchedEffect(requestKey, generation, asyncState) {
            when (asyncState) {
                is AsyncImagePainter.State.Success -> {
                    memory.settle(requestKey, generation, asyncState.painter, track)
                }
                is AsyncImagePainter.State.Error -> {
                    if (companionMediaArtworkCompletionAccepted(
                            memory.requestKey,
                            memory.generation,
                            requestKey,
                            generation,
                        )
                    ) {
                        memory.displayedState = CompanionMediaArtworkLoadState.ERROR
                    }
                }
                else -> {
                    if (companionMediaArtworkCompletionAccepted(
                            memory.requestKey,
                            memory.generation,
                            requestKey,
                            generation,
                        )
                    ) {
                        memory.displayedState = CompanionMediaArtworkLoadState.LOADING
                    }
                }
            }
            revision += 1
        }
    }
    if (track != null && requestKey != null) {
        val generation = memory.generation
        LaunchedEffect(requestKey, generation) {
            delay(CompanionMediaTransientGraceMs)
            revision += 1
        }
    }
    val candidateState = companionMediaArtworkCandidateState(
        hasDirectPainter = directPainter != null,
        localFileGone = localFileGone,
        asyncSuccess = asyncState is AsyncImagePainter.State.Success,
        asyncError = asyncState is AsyncImagePainter.State.Error,
        trackState = track?.artworkLoadState,
    )
    val sameSession = track != null && memory.displayedTrack?.sessionIdentity == track.sessionIdentity
    val retainPrevious = track != null && memory.displayedPainter != null &&
        companionMediaArtworkMayRetainPrevious(
            sameSession = sameSession,
            retentionStartedAtElapsedMs = memory.retentionStartedAtElapsedMs,
            nowElapsedMs = now,
            candidateState = candidateState,
        )
    val currentReadyPainter = when {
        directPainter != null -> directPainter
        asyncState is AsyncImagePainter.State.Success -> asyncState.painter
        memory.displayedRequestKey == requestKey && memory.displayedState == CompanionMediaArtworkLoadState.READY ->
            memory.displayedPainter
        else -> null
    }
    val visualTrack = when {
        retainPrevious -> memory.displayedTrack
        track != null -> track
        else -> null
    }
    val visualPainter = if (retainPrevious) memory.displayedPainter else currentReadyPainter
    return CompanionResolvedMediaArtwork(
        painter = visualPainter,
        state = if (retainPrevious) CompanionMediaArtworkLoadState.READY else candidateState,
        visualTrack = visualTrack,
        pendingVisualCommit = track != null && (
            retainPrevious ||
                (candidateState == CompanionMediaArtworkLoadState.LOADING && visualPainter == null) ||
                visualTrack?.presentationTrackIdentity != track.presentationTrackIdentity
        ),
        revision = revision,
    )
}

/** Shared between the page swipe detector and the child seek detector. */
internal class CompanionSeekGestureGate {
    var serial: Long = 0L
    var active: Boolean = false

    fun begin() {
        serial += 1L
        active = true
    }

    fun finish() {
        active = false
    }
}

private enum class CompanionActivityScene {
    ACTIVITY,
    ACHIEVEMENTS,
    TAKEOVER,

    /** Main's music panel at full size (W2d): the media tile opens it. It outranks the takeover, the hub and the resting layout. */
    MUSIC,
}

@Composable
internal fun CompanionWorkspaceSurface(
    page: CompanionWorkspacePage,
    navigationEnabled: Boolean,
    recentApp: CompanionAppEntry?,
    game: MimirGameSession?,
    media: CompanionMediaUiState,
    onRecentApp: (CompanionAppEntry) -> Unit,
    onReturnToGame: ((com.gruning.mimir.rewrite.core.model.GameId) -> Boolean)?,
    onMediaAccess: () -> Unit,
    onMediaAction: (CompanionMediaAction, MediaSession.Token, String) -> Unit,
    onMediaSeek: (Long, MediaSession.Token, String) -> Unit = { _, _, _ -> },
    achievementSnapshots: List<CompanionActivityAchievementSnapshot> = emptyList(),
    achievementHubAccessEnabled: Boolean = false,
    /** The user's total RetroAchievements points from the cold-start bulk read, or null when it was not read. */
    achievementTotalPoints: Int? = null,
    /** The user's softcore score beside it (0 when none or not read): never added to the hardcore one. */
    achievementSoftcorePoints: Int = 0,
    /** Nothing is known about the account's achievements yet (its ledger is not read and no bulk read has ended): the strip says it is checking. */
    achievementsChecking: Boolean = false,
    /** The user's latest unlocks across every game (the achievements hub's badge strip); empty hides the strip. */
    achievementRecent: List<ActivityRecentUnlock> = emptyList(),
    achievementArtResolver: ActivityAchievementArtResolver = NoActivityAchievementArt,
    achievementBadgeSource: ActivityBadgeSource = NoActivityBadgeArt,
    /** What the achievements hub's card says when there is no snapshot to show (the card's own Unknown copy, by cause). */
    achievementHubUnknownState: MimirAchievementCardState = MimirAchievementCardState.Unknown(MimirAchievementCopy.NothingCheckedYet),
    bento: ActivityBentoData = EmptyActivityBentoData,
    onOpenGame: (GameRow) -> Unit = {},
    /** The accessible "Return to Shelf" (pad9): the same transition as the swipe. True when it began. Null where the host has no strip. */
    onReturnToShelf: (() -> Boolean)? = null,
    gameCardAvailableGameId: GameId? = null,
    gameCardSessionKey: String? = null,
    /**
     * The Now Playing takeover, built by the Presentation from the live session; the surface hands it the two things only it can do: go back to the
     * resting layout, and open the achievements hub.
     */
    gameCardContent: (@Composable (onBack: () -> Unit, onOpenAchievements: () -> Unit) -> Unit)? = null,
    /** The takeover's open session key when the host holds it (process state, so a remount keeps it); null with no [onTakeoverKeyChange] means the surface holds it itself. */
    takeoverKey: String? = null,
    onTakeoverKeyChange: ((String?) -> Unit)? = null,
    showActivityContent: Boolean = true,
    showRecentAppOverlay: Boolean = false,
    /**
     * The workspace seam (B4), the same three callbacks the lattice offers: a horizontal drag anywhere on the Activity page that no child claims moves
     * the host's workspace strip with the finger. [onWorkspaceDrag] takes a finger delta in px (positive is rightward), [onWorkspaceDragEnd] the release
     * (px/s) and answers whether the host held it, and [onWorkspaceDragCancel] a drag that ended without a release. Null: the page does not pan.
     */
    onWorkspaceDrag: ((deltaPx: Float) -> Float)? = null,
    onWorkspaceDragEnd: ((velocityPx: Float) -> Boolean)? = null,
    onWorkspaceDragCancel: (() -> Unit)? = null,
    /** What the music panel (opened from the media tile) plays and drives: the process's music control in production, a fake in a test. */
    musicPanelEngine: MusicPanelEngine = MimirMusicPanelEngine,
    /** The system's media volume the music panel's Game & media slider moves; null builds Main's own gateway from the context. */
    musicPanelMediaVolume: MediaVolumeGateway? = null,
    /** Whether the full music player is open when the host holds it (process state, [CompanionMusicPlayerState]); read only with [onMusicPlayerOpenChange]. */
    musicPlayerOpen: Boolean = false,
    /** The host's change of [musicPlayerOpen]; null means the surface holds the flag itself. */
    onMusicPlayerOpenChange: ((Boolean) -> Unit)? = null,
    /** Whether the Bottom's window is up and lit: the achievements hub's pad route follows it (the window parks without parking this pane). A host that is always live passes nothing. */
    windowLive: CompanionWindowLive = CompanionWindowLive.Always,
) {
    val palette = LocalMimirPalette.current
    val activityControl = rememberBottomUiControl(BottomSurface.ACTIVITY)
    val latestEnabled by rememberUpdatedState(navigationEnabled)
    val latestWorkspaceDrag by rememberUpdatedState(onWorkspaceDrag)
    val latestWorkspaceDragEnd by rememberUpdatedState(onWorkspaceDragEnd)
    val latestWorkspaceDragCancel by rememberUpdatedState(onWorkspaceDragCancel)
    val latestWorkspaceDragCancelHolder = rememberUpdatedState(onWorkspaceDragCancel)
    val latestRecentEnabled by rememberUpdatedState(navigationEnabled)
    val latestRecentShown by rememberUpdatedState(showRecentAppOverlay)
    // Whether the recent-app tile has ever been asked for: written and read in this composition only (an idempotent holder, not state).
    val recentAppShownOnce = remember { booleanArrayOf(false) }
    val seekGate = remember { CompanionSeekGestureGate() }
    val activeTrack = (media as? CompanionMediaUiState.Active)?.track?.takeIf {
        it.playbackState != CompanionMediaPlaybackState.UNAVAILABLE || it.transientGap
    }
    val resolvedMediaArtwork = rememberCompanionMediaArtwork(activeTrack)
    // Whether the host holds this drag, and the seek gate's serial when it began (see the drag state below).
    val workspaceDragLive = remember { booleanArrayOf(false) }
    val workspaceDragSerial = remember { longArrayOf(0L) }
    // The takeover's open session: the host's (process state, so a Presentation remount keeps it and the entry glide can set it) when it gives
    // a change callback, else this surface's own.
    var localTakeoverKey by remember { mutableStateOf<String?>(null) }
    val selectedGameSessionKey = if (onTakeoverKeyChange != null) takeoverKey else localTakeoverKey
    val latestTakeoverChange by rememberUpdatedState(onTakeoverKeyChange)
    fun selectTakeover(key: String?) {
        val change = latestTakeoverChange
        if (change != null) change(key) else localTakeoverKey = key
    }
    var achievementHubOpen by remember { mutableStateOf(false) }
    // The game the hub opens on, when it is opened from that game's Now Playing page; null (the Activity page's way in) opens it at its first card, the newest activity.
    var achievementHubFocusGameId by remember { mutableStateOf<String?>(null) }
    // The music player the media tile opens (W2d): the host's process state when it gives a change callback (Y0, so a trip to the shelf, where the Activity scene leaves
    // composition, finds the player as it was left), else this surface's own.
    var localMusicPanelOpen by remember { mutableStateOf(false) }
    val musicPanelOpen = if (onMusicPlayerOpenChange != null) musicPlayerOpen else localMusicPanelOpen
    val latestMusicPlayerChange by rememberUpdatedState(onMusicPlayerOpenChange)
    fun setMusicPanelOpen(open: Boolean) {
        val change = latestMusicPlayerChange
        if (change != null) change(open) else localMusicPanelOpen = open
    }
    // The panel's own model, read from the engine the panel is built on: it decides whether the lower-left tile is a media tile and which card it shows (W2e).
    val engineState by musicPanelEngine.state.collectAsStateWhileLive()
    val engineCard = remember(engineState) { musicTileCard(engineState) }
    // The bridge's richer tile stands in only while it is the player the panel's top card shows (the same package: bridgeTrackIsEnginePlayer).
    // During a player switch the bridge is ahead of the engine, and the engine's own card is the tile until they agree, so tile and panel never name two players.
    val engineOther = engineState.other
    val bridgeIsTopCard = engineCard?.card == MusicPanelCard.OTHER && activeTrack != null && engineOther != null && bridgeTrackIsEnginePlayer(activeTrack, engineOther)
    // The panel replaces the takeover as the scene (it outranks it); the takeover's own key is cleared too, so Back lands on the resting layout.
    val openMusicPanel: () -> Unit = {
        selectTakeover(null)
        setMusicPanelOpen(true)
    }
    val detailState = companionActivityDetailState(
        selectedGameSessionKey, gameCardSessionKey,
        game != null && gameCardAvailableGameId == game.gameId && gameCardContent != null,
        navigationEnabled,
    )
    val detailAvailable = detailState.canOpen
    // A game session is running with its Now Playing page available: the hub's Back then reads "Session" and returns to that page, wherever the hub was opened from.
    val sessionPageAvailable = game != null && gameCardSessionKey != null && gameCardAvailableGameId == game.gameId && gameCardContent != null
    // When each launched game was last played: with the last unlock it orders the hub's games, newest activity first.
    val lastPlayedByGame = remember(bento.launched) {
        bento.launched.orEmpty().mapNotNull { row -> row.lastPlayedMillis?.let { played -> row.gameId.value to played } }.toMap()
    }
    LaunchedEffect(detailState.selectedSessionKey) {
        selectTakeover(detailState.selectedSessionKey)
    }
    // The music panel outranks the takeover, and an automatic entry (the launch glide sets the host's key) while the panel is open is dropped, not queued:
    // the player is on the panel, and Back lands on the resting layout. Declared after the effect above so, when both run for one change, this one is last.
    LaunchedEffect(musicPanelOpen, detailState.selectedSessionKey) {
        if (musicPanelOpen && detailState.selectedSessionKey != null) selectTakeover(null)
    }
    // The timeline's scrubber claims the DOWN in the Initial pass (below), so a drag that starts on it never reaches this one. A timeline that claims the
    // pointer stream AFTER this drag began changes the gate's serial: the workspace lets go and goes back to where it started, so a scrub never moves it.
    // A second finger that goes down mid-drag takes the gesture away: the workspace goes back to where it began at once, and what is left of the gesture is ignored.
    val workspaceWatch = remember {
        MimirGestureWatch {
            if (workspaceDragLive[0]) {
                workspaceDragLive[0] = false
                latestWorkspaceDragCancelHolder.value?.invoke()
            }
        }
    }
    val workspaceDragState = rememberDraggableState { delta ->
        if (workspaceDragLive[0]) {
            if (seekGate.serial != workspaceDragSerial[0]) {
                workspaceDragLive[0] = false
                latestWorkspaceDragCancel?.invoke()
            } else {
                latestWorkspaceDrag?.invoke(delta)
            }
        }
    }
    val activitySwipeModifier = if (page == CompanionWorkspacePage.ACTIVITY && onWorkspaceDrag != null) {
        Modifier.mimirGestureWatch(workspaceWatch).draggable(
            state = workspaceDragState,
            orientation = Orientation.Horizontal,
            enabled = navigationEnabled,
            onDragStarted = {
                workspaceDragLive[0] = latestEnabled
                workspaceDragSerial[0] = seekGate.serial
            },
            onDragStopped = { velocity ->
                if (workspaceDragLive[0]) {
                    workspaceDragLive[0] = false
                    // Only a lift decides. A drag the system cancelled (a stop with no release before it) or a second finger touched settles back, with no receipt.
                    if (!workspaceWatch.takenAway && latestEnabled && seekGate.serial == workspaceDragSerial[0]) latestWorkspaceDragEnd?.invoke(velocity) else latestWorkspaceDragCancel?.invoke()
                }
            },
        )
    } else {
        Modifier
    }
    // The page root, which holds the resting layout AND every subsurface (takeover, hub, music): a screen reader finds the way back from any of them.
    val returnAction = if (page == CompanionWorkspacePage.ACTIVITY && navigationEnabled) onReturnToShelf?.let { go -> { if (latestEnabled) go() else false } } else null
    // The recent-app tile's header slot (CompanionBottomHeaderSlot.kt): every scene's top row reads it, so no page is drawn under the tile.
    val headerReserve = bottomHeaderTrailingReserveFor(recentAppShown = recentApp != null)
    CompositionLocalProvider(LocalBottomHeaderTrailingReserve provides headerReserve) {
    Box(Modifier.fillMaxSize().then(activitySwipeModifier).activityReturnAction(returnAction)) {
        if (showActivityContent && page == CompanionWorkspacePage.ACTIVITY) {
            val takeoverShown = detailState.showGame
            // The swap tier (design section 4): the resting layout, the Now Playing takeover, the achievements hub and the music panel are pages replacing one
            // another in place. 8 dp of travel, the departing page fading on OverlayMs, the arriving one after the 130 ms lead; a return (to the resting
            // layout) takes the page tier's return clock. Read at each swap edge, so what is drawn and what is reported are one answer; reduced
            // motion snaps every spec. The Bottom is touch-only, so the page gate never holds the process-wide pad gate (holdPad = false).
            val workspaceScene = when {
                musicPanelOpen -> CompanionActivityScene.MUSIC
                takeoverShown -> CompanionActivityScene.TAKEOVER
                achievementHubOpen -> CompanionActivityScene.ACHIEVEMENTS
                else -> CompanionActivityScene.ACTIVITY
            }
            // D24: a swap that lands while this pane is PARKED (Now Playing closes under a swipe to the shelf) snaps, with no animation to run behind a pane nobody
            // can see. Read at the swap edge, outside observation, so a pane parking or returning does not recompose the page.
            // The same holds for a WINDOW parked behind an app or dark (the earlier update, Thor 2026-10-08): its frame clock is stopped, so a swap that lands then started a
            // crossfade and glide that could not finish (`transition.unfinished`) and froze mid-fade until the window came back. Snapped, the new scene is already
            // whole when it does. [windowLive] is plain flow state, read here and never observed.
            val paneMotionLive = LocalMimirMotionLive.current
            val swapReduced = remember(workspaceScene) {
                companionSwapSnaps(bottomUiReducedMotion(), Snapshot.withoutReadObservation { paneMotionLive.value }, windowLive.isLive)
            }
            val swapReturning = workspaceScene == CompanionActivityScene.ACTIVITY
            val swapPx = with(LocalDensity.current) { MimirMotion.SwapTravelDp.dp.roundToPx() }
            AnimatedContent(
                targetState = workspaceScene,
                transitionSpec = {
                    mimirSwapTransform(swapReduced, returning = swapReturning, returnTravelPx = swapPx, enterTravelPx = swapPx)
                },
                label = "activity-workspace-swap",
                modifier = Modifier.fillMaxSize(),
            ) { shownScene ->
            val pageInput = MimirSwapPageGate(mimirSwapFadeAlpha(swapReduced, returning = swapReturning), holdPad = false)
            Box(Modifier.fillMaxSize().then(pageInput)) {
            // The receipts the scene host wrote before the swap tier replaced it: a scene change is `scene.crossfade` and `scene.glide`, begun when
            // the change starts and ended when the real animation settles (measured, not assumed). Only the page that is the current target reports,
            // so the page being left does not write a second run for the same swap, and the first scene on screen writes nothing.
            if (shownScene == workspaceScene) {
                BottomUiTransitionProbe("scene.crossfade", transition)
                BottomUiTransitionProbe("scene.glide", transition)
            }
            // The music backdrop belongs to the resting layout and the hub, never to the takeover: with no game hero the takeover shows the bare
            // palette, not a blurred album behind a game's page. It lives inside the swapped page, so it dissolves with the page it belongs to.
            if (shownScene != CompanionActivityScene.TAKEOVER && activeTrack != null && resolvedMediaArtwork.painter != null) {
                MediaArtworkBackdrop(resolvedMediaArtwork)
            }
            if (shownScene == CompanionActivityScene.MUSIC) {
                // The music panel's surface receipt (W2d), written by the page that IS the panel: its entry is the swap that brought it in (scene.crossfade /
                // scene.glide above, no transition of its own), exactly as the media tile's is, and its exit is the page leaving composition when the return has
                // settled. It is not a flag every scene reports: a page being left would report "hidden" for the same surface and close the panel's entry under it.
                BottomUiSurfaceReceipt(surface = BottomSurface.MUSIC_PANEL, visible = true)
                CompanionMusicPanelScene(
                    engine = musicPanelEngine,
                    mediaVolume = rememberCompanionMusicPanelMediaVolume(musicPanelMediaVolume),
                    navigationEnabled = navigationEnabled,
                    onBack = { if (latestEnabled) setMusicPanelOpen(false) },
                    // The bridge's track is the player's position, length and seek only while it is the engine's top player (the same package), so the bar and the
                    // card name one player; Your music's come from the engine. The seek is the tile's own path, through the same gate.
                    bridgeTrack = activeTrack?.takeIf { bridgeIsTopCard },
                    seekGate = seekGate,
                    onBridgeSeek = { position -> activeTrack?.let { onMediaSeek(position, it.sessionIdentity, it.trackIdentity) } },
                )
            } else if (shownScene == CompanionActivityScene.TAKEOVER) {
                gameCardContent?.invoke(
                    {
                        if (latestEnabled) {
                            // Receipt unchanged: the back pill on the old session detail wrote this id.
                            activityControl("activity.back_to_activity", null)
                            selectTakeover(null)
                        }
                    },
                    {
                        if (latestEnabled) {
                            // The hub is a scene of its own; Back from it returns to this page while the session runs. It opens on THIS game's card.
                            achievementHubFocusGameId = game?.gameId?.value
                            selectTakeover(null)
                            achievementHubOpen = true
                        }
                    },
                )
            } else {
            if (shownScene == CompanionActivityScene.ACHIEVEMENTS) {
                CompanionActivityAchievementHub(
                    snapshots = achievementSnapshots,
                    unknownState = achievementHubUnknownState,
                    navigationEnabled = navigationEnabled,
                    onBack = {
                        if (latestEnabled) {
                            achievementHubOpen = false
                            if (sessionPageAvailable) selectTakeover(gameCardSessionKey)
                        }
                    },
                    backLabel = if (sessionPageAvailable) "Session" else "Activity",
                    focusGameId = achievementHubFocusGameId,
                    lastPlayed = lastPlayedByGame,
                    artResolver = achievementArtResolver,
                    badgeSource = achievementBadgeSource,
                    // Only the scene on show takes the pad: while the swap carries the hub away (back to the page, the music panel, a card) it is already given back.
                    padLive = workspaceScene == CompanionActivityScene.ACHIEVEMENTS,
                    windowLive = windowLive,
                )
            } else {
                // Receipt (unchanged from the stacked layout): the media tile on the Activity page is the Bottom's music surface in small, and a tap on
                // it opens the full panel (BottomSurface.MUSIC_PANEL). The tile has no enter transition of its own (it rides the swap that brought the
                // Activity page in); a track change is scene.crossfade / scene.glide.
                BottomUiSurfaceReceipt(surface = BottomSurface.ACTIVITY_MEDIA, visible = activeTrack != null || engineCard != null)
                ActivityPage(
                    data = bento,
                    navigationEnabled = navigationEnabled,
                    // The lower-left tile is the media tile whenever Main's music panel would have a card (W2e), Your music included, built from the panel's own model
                    // (musicTileCard); a bridge track for another player keeps its richer tile. Otherwise it is the media tile for every media state but one: access
                    // still to be granted and a session Android would not hand over keep their honest copy and recovery guidance. Only "available, nothing
                    // playing" gives the tile to Been a while.
                    mediaTile = when {
                        engineCard != null && !bridgeIsTopCard -> @Composable { tileModifier: Modifier ->
                            ActivityEngineMediaTile(
                                card = engineCard,
                                engine = musicPanelEngine,
                                navigationEnabled = navigationEnabled,
                                onOpenPanel = openMusicPanel,
                                modifier = tileModifier,
                            )
                        }
                        activeTrack != null -> @Composable { tileModifier: Modifier ->
                            ActivityMediaTile(
                                track = activeTrack,
                                resolvedArtwork = resolvedMediaArtwork,
                                navigationEnabled = navigationEnabled,
                                seekGate = seekGate,
                                onAction = onMediaAction,
                                onSeek = onMediaSeek,
                                // Only while the panel has a card to show: a tile the panel could not follow does not open it.
                                onOpenPanel = openMusicPanel.takeIf { engineCard != null },
                                modifier = tileModifier,
                            )
                        }
                        media == CompanionMediaUiState.AccessRequired -> @Composable { tileModifier: Modifier ->
                            ActivityMediaStateTile(access = true, navigationEnabled = navigationEnabled, onMediaAccess = onMediaAccess, modifier = tileModifier)
                        }
                        media == CompanionMediaUiState.SessionUnavailable || media is CompanionMediaUiState.Active -> @Composable { tileModifier: Modifier ->
                            ActivityMediaStateTile(access = false, navigationEnabled = navigationEnabled, onMediaAccess = onMediaAccess, modifier = tileModifier)
                        }
                        else -> null
                    },
                    achievementSnapshots = achievementSnapshots,
                    achievementsAccessEnabled = achievementHubAccessEnabled,
                    achievementTotalPoints = achievementTotalPoints,
                    achievementSoftcorePoints = achievementSoftcorePoints,
                    achievementsChecking = achievementsChecking,
                    onOpenAchievements = {
                        if (latestEnabled && !detailState.showGame) {
                            activityControl("activity.open_achievements", null)
                            achievementHubFocusGameId = null
                            selectTakeover(null)
                            achievementHubOpen = true
                        }
                    },
                    onOpenGame = onOpenGame,
                    onReturnToShelf = returnAction,
                    onOpenLiveGame = {
                        if (detailState.canOpen) {
                            activityControl("activity.open_game_detail", null)
                            selectTakeover(gameCardSessionKey)
                        }
                    }.takeIf { detailAvailable },
                    // The recent-app icon the Presentation lays over this page's top end: the header's stat stops short of it.
                    trailingReserve = headerReserve,
                )
            }
            }
            }
            }
        }
        // Once shown, the tile stays composed on the Activity page and steps aside on alpha while a launch card or a picker is over the page, so it neither pops out as
        // they open nor pops back as they close (and its icon is not decoded again each time). A surface that never shows it (the page's own copy) composes nothing.
        // Faded out it is inert: no touch, no semantics, so the corner tap the cover protects still reaches the layer that closes on it.
        if (showRecentAppOverlay) recentAppShownOnce[0] = true
        if (page == CompanionWorkspacePage.ACTIVITY && recentAppShownOnce[0]) {
            recentApp?.let { recent ->
                val recentSource = remember { MutableInteractionSource() }
                val recentAlpha by animateFloatAsState(
                    targetValue = if (showRecentAppOverlay) 1f else 0f,
                    animationSpec = tween(if (mimirReducedMotion()) 0 else MimirMotion.OverlayMs, easing = MimirMotion.Easing),
                    label = "activity-recent-app-tile",
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = RecentAppTileTopDp.dp, end = RecentAppTileEndDp.dp)
                        // 48 dp, the touch minimum (audit C2, Thor Bottom revamp V8): it was 44. ActivityRecentAppReserveDp follows it.
                        .size(MimirSpacing.minTarget)
                        .testTag(ActivityRecentAppTileTag)
                        // The fade is read in the layer, never in composition.
                        .graphicsLayer { alpha = recentAlpha }
                        // The Bottom's one press response (B5, R4): after the tap and before the fill, so the icon's ground goes with it and the hit box stays whole.
                        .mimirActivatable(
                            label = "Open recent app ${recent.label}",
                            contentDescription = "Open recent app ${recent.label}",
                            enabled = navigationEnabled && showRecentAppOverlay,
                            interactionSource = recentSource,
                            onActivate = {
                                if (latestRecentEnabled && latestRecentShown) {
                                    activityControl("activity.recent_app", recent.packageName)
                                    onRecentApp(recent)
                                }
                            },
                        )
                        .mimirPressResponse(recentSource)
                        .clip(RoundedCornerShape(MimirSpacing.radiusCard))
                        .background(palette.surfaceHigh.copy(alpha = 0.82f)),
                    contentAlignment = Alignment.Center,
                ) {
                    RecentAppIcon(recent)
                }
            }
        }
    }
    }
}

/**
 * The media tile when there is no track to show but media is not simply idle: access still to be granted, or a session Android did not hand
 * over (or a playing app that withdrew its metadata for good). The same words the stacked layout used ("Connect your media" with how, "Media
 * unavailable" with how to recover), in the tile's grammar: one accent eyebrow, a title, one explaining line, and for access the one 48 dp action.
 */
@Composable
private fun ActivityMediaStateTile(
    access: Boolean,
    navigationEnabled: Boolean,
    onMediaAccess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MimirLiquidGlassPanel(
        modifier = modifier.fillMaxWidth(),
        contentPadding = 10.dp,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        radius = MimirSpacing.radiusCard,
        gradientBody = false,
    ) {
        val palette = LocalMimirPalette.current
        MimirChromeText("Media", style = MimirTypeScale.RunicEyebrow, color = palette.accent, maxLines = 1)
        Text(
            text = if (access) "Connect your media" else "Media unavailable",
            modifier = Modifier.testTag(ActivityMediaStateTag),
            style = MimirTypeScale.Row,
            color = palette.textPrimary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = if (access) {
                "Allow notification access to read playback from your media apps."
            } else {
                "Reconnect the source app to resume playback controls."
            },
            style = MimirTypeScale.SupportCompact,
            color = palette.textSecondary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (access) {
            MediaButton(
                label = "Open notification access",
                enabled = navigationEnabled,
                modifier = Modifier.heightIn(min = 48.dp),
                pressResponse = true,
                // An unselected etched chip (update; it was an accent-tinted capsule): the 48 dp hit box above is the whole button, and the metal plate is the chip's own
                // height inside it (the 8 dp above and below it plus the label's own 8 dp padding), so the plate is not 48 dp tall.
                visual = Modifier
                    .padding(vertical = MimirSpacing.sm)
                    .mimirEtchedChipSurface(palette, warmed = false),
                onClick = onMediaAccess,
            )
        }
    }
}

/**
 * The media tile (T4 of the Activity bento): the same track card as before, in the grammar of Main's music panel, sized for a tile. The art
 * (the shared [MediaArtwork], so R3/R8 hold: no plate over wired art, nothing while it loads or after it fails) sits beside the status eyebrow
 * and the title, under it the 48 dp seek lane, under that the transport: Previous and Next 48 dp, Play/Pause 52 dp, 14 dp apart.
 * A track change glides the art and title exactly as the old card did.
 */
@Composable
private fun ActivityMediaTile(
    track: CompanionMediaTrack,
    resolvedArtwork: CompanionResolvedMediaArtwork,
    navigationEnabled: Boolean,
    seekGate: CompanionSeekGestureGate,
    onAction: (CompanionMediaAction, MediaSession.Token, String) -> Unit,
    onSeek: (Long, MediaSession.Token, String) -> Unit,
    onOpenPanel: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val visualTrack = resolvedArtwork.visualTrack ?: track
    val visualIdentity = visualTrack.presentationTrackIdentity
    val transitionIdentity = track.sessionIdentity to visualIdentity
    val incomingOffset = when (track.transitionDirection ?: visualTrack.transitionDirection) {
        CompanionMediaTransitionDirection.PREVIOUS -> -MimirMotion.GlideTravelDp.toFloat()
        CompanionMediaTransitionDirection.NEXT -> MimirMotion.GlideTravelDp.toFloat()
        null -> MimirMotion.GlideTravelDp.toFloat()
    }
    val statusLabel = if (track.transientGap) "Switching" else track.playbackState.displayLabel()
    val mediaControl = rememberBottomUiControl(BottomSurface.ACTIVITY_MEDIA)
    val latestOpenPanel by rememberUpdatedState(onOpenPanel)
    val openSource = remember { MutableInteractionSource() }
    MimirLiquidGlassPanel(
        // The whole tile opens Main's music panel (W2d). The pointer node is first and the press response right after it (B5, R4), so the tile draws in
        // whole and its hit box stays whole. The transport buttons and the seek lane are children that take a touch first, so a press on them never reaches
        // this; a press anywhere else on the tile (the art, the title, the gaps) opens the panel.
        modifier = modifier.fillMaxWidth()
            .mimirActivatable(
                label = "Open music panel",
                enabled = navigationEnabled && onOpenPanel != null,
                interactionSource = openSource,
                onActivate = {
                    if (latestOpenPanel != null) mediaControl("activity-media.open_panel", null)
                    latestOpenPanel?.invoke()
                },
            )
            .mimirPressResponse(openSource),
        contentPadding = 10.dp,
        verticalArrangement = Arrangement.spacedBy(0.dp),
        radius = MimirSpacing.radiusCard,
        gradientBody = false,
    ) {
        val palette = LocalMimirPalette.current
        CompanionSceneTransition(
            targetState = visualIdentity,
            transitionIdentity = transitionIdentity,
            incomingGlideOffsetDp = incomingOffset,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            // The departing row is the recorded last frame of the one that was here (core-ui's MimirPageHandoff): a child that survives the
            // flip would re-record as the NEW track while the old one fades, so the row is keyed on the track it shows.
            key(transitionIdentity) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MediaArtwork(track = visualTrack, resolvedArtwork = resolvedArtwork, size = 56.dp)
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        MimirChromeText(
                            statusLabel,
                            style = MimirTypeScale.RunicEyebrow,
                            color = if (track.playbackState == CompanionMediaPlaybackState.PLAYING && !track.transientGap) {
                                palette.accent
                            } else {
                                palette.textSecondary
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = visualTrack.title,
                            style = MimirTypeScale.Row,
                            color = palette.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        MediaTimeline(
            track = track,
            navigationEnabled = navigationEnabled,
            seekGate = seekGate,
            onSeek = onSeek,
            modifier = Modifier.fillMaxWidth(),
        )
        // "Enabled" is the player's say only: it also draws the look, so the dock's and the strip's settle must not (the buttons flashed disabled on return to Activity).
        // A tap during the settle is dropped in onClick through latestNavigationEnabled instead.
        val latestNavigationEnabled by rememberUpdatedState(navigationEnabled)
        val transportEnabled = !track.transientGap && track.playbackState !in setOf(
            CompanionMediaPlaybackState.BUFFERING,
            CompanionMediaPlaybackState.CONNECTING,
            CompanionMediaPlaybackState.SKIPPING,
            CompanionMediaPlaybackState.UNAVAILABLE,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MediaButton(
                label = "Previous",
                enabled = transportEnabled && track.canPrevious,
                skipTargetSize = MediaTileSkipTargetDp,
                skipPlateSize = MediaTileSkipPlateDp,
                pressResponse = true,
                onClick = {
                    if (latestNavigationEnabled) {
                        mediaControl("activity-media.previous", null)
                        onAction(CompanionMediaAction.PREVIOUS, track.sessionIdentity, track.trackIdentity)
                    }
                },
            )
            MediaButton(
                label = if (track.playbackState == CompanionMediaPlaybackState.PLAYING) "Pause" else "Play",
                enabled = transportEnabled && track.canPlayPause,
                primary = true,
                primarySize = MediaTilePlayDp,
                pressResponse = true,
                onClick = {
                    if (latestNavigationEnabled) {
                        mediaControl("activity-media.play_pause", null)
                        onAction(CompanionMediaAction.PLAY_PAUSE, track.sessionIdentity, track.trackIdentity)
                    }
                },
            )
            MediaButton(
                label = "Next",
                enabled = transportEnabled && track.canNext,
                skipTargetSize = MediaTileSkipTargetDp,
                skipPlateSize = MediaTileSkipPlateDp,
                pressResponse = true,
                onClick = {
                    if (latestNavigationEnabled) {
                        mediaControl("activity-media.next", null)
                        onAction(CompanionMediaAction.NEXT, track.sessionIdentity, track.trackIdentity)
                    }
                },
            )
        }
    }
}

@Composable
private fun MediaArtwork(
    track: CompanionMediaTrack,
    resolvedArtwork: CompanionResolvedMediaArtwork,
    size: androidx.compose.ui.unit.Dp,
) {
    val palette = LocalMimirPalette.current
    Box(
        modifier = Modifier
            .size(size)
            .testTag(ActivityMediaArtTag)
            .clip(RoundedCornerShape(14.dp))
            // The plate is the no-art fallback's seat, exactly as Main's music panel seats it (R3, R8): it draws only when no art is
            // wired. A load in progress, a failed load and a ready bitmap all draw without it.
            .then(
                if (companionMediaPlateVisible(resolvedArtwork.state, hasPainter = resolvedArtwork.painter != null)) {
                    Modifier.background(palette.surfaceHigh)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        MediaArtworkContent(track, resolvedArtwork)
    }
}

/** Test tag of the blurred music backdrop behind the resting layout. */
internal const val MediaBackdropTag = "activity-media-backdrop"

/** The recent-app tile laid over the Activity page's top end (see [CompanionWorkspaceSurface]'s `showRecentAppOverlay`). */
internal const val ActivityRecentAppTileTag = "activity-recent-app-tile"

@Composable
private fun BoxScope.MediaArtworkBackdrop(resolvedArtwork: CompanionResolvedMediaArtwork) {
    val palette = LocalMimirPalette.current
    Box(
        modifier = Modifier
            .matchParentSize()
            .blur(30.dp)
            .clearAndSetSemantics { testTag = MediaBackdropTag },
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(palette.surfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            resolvedArtwork.visualTrack?.let { track ->
                MediaArtworkContent(track, resolvedArtwork, contentDescription = null)
            }
        }
    }
    Box(
        modifier = Modifier
            .matchParentSize()
            .background(palette.surfaceBase.copy(alpha = 0.58f))
            .clearAndSetSemantics { },
    )
}

@Composable
private fun MediaArtworkContent(
    track: CompanionMediaTrack,
    resolvedArtwork: CompanionResolvedMediaArtwork,
    contentDescription: String? = "Artwork for ${track.title}",
) {
    val painter = resolvedArtwork.painter
    val mediaArtState = resolvedArtwork.state
    // Mirrors Main's `MusicPanelArtwork` (R3, R8): LOADING draws nothing, ERROR draws nothing, the fallback glyph (on the plate
    // [MediaArtwork] seats) is for NO_ART only, and art that arrives fades in on the house overlay clock.
    val artAlpha by animateFloatAsState(
        targetValue = if (painter != null) 1f else 0f,
        animationSpec = tween(if (mimirReducedMotion()) 0 else MimirMotion.OverlayMs, easing = MimirMotion.Easing),
        label = "activity-media-art",
    )
    // Receipt (the foreground artwork only; the blurred backdrop copy passes no description and stays silent). Its facts are what
    // the draw below actually does: `plateVisible` is the plate and glyph this site draws (NO_ART only), and `hasArt` is wired (a
    // failed art is still wired), so a load in progress reads `hasArt=true state=loading plate=hidden` and a failed one
    // `hasArt=true state=failed plate=hidden` (no breach).
    if (contentDescription != null) {
        val facts = companionMediaArtReceiptFacts(mediaArtState, hasPainter = painter != null)
        BottomUiArtReceipt(
            tile = "activity.media",
            hasArt = facts.hasArt,
            loaded = facts.loaded,
            failed = facts.failed,
            plateVisible = facts.plateVisible,
        )
    }
    if (painter != null) {
        Image(
            painter = painter,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = artAlpha },
        )
    } else if (mediaArtState == CompanionMediaArtworkLoadState.NO_ART) {
        MediaArtFallbackGlyph(track.title, palette = LocalMimirPalette.current, showTitle = false, fitTitle = true)
    }
}

/** The plate behind the Activity media art is the no-art fallback's seat: it exists only when no art is wired (R3, R8). */
internal fun companionMediaPlateVisible(state: CompanionMediaArtworkLoadState, hasPainter: Boolean): Boolean =
    !hasPainter && state == CompanionMediaArtworkLoadState.NO_ART

/** What the `activity.media` receipt reports for the state [MediaArtworkContent] actually draws. */
internal data class CompanionMediaArtReceiptFacts(
    val hasArt: Boolean,
    val loaded: Boolean,
    val failed: Boolean,
    val plateVisible: Boolean,
)

internal fun companionMediaArtReceiptFacts(
    state: CompanionMediaArtworkLoadState,
    hasPainter: Boolean,
): CompanionMediaArtReceiptFacts {
    val wired = hasPainter || state != CompanionMediaArtworkLoadState.NO_ART
    val failed = !hasPainter && state == CompanionMediaArtworkLoadState.ERROR
    return CompanionMediaArtReceiptFacts(
        hasArt = wired,
        loaded = hasPainter,
        failed = failed,
        plateVisible = companionMediaPlateVisible(state, hasPainter),
    )
}

/**
 * Where [track] is at [nowElapsedRealtimeMs]: the stored callback position carried forward by the elapsed time at the session's speed while it
 * PLAYS, the stored position as it is while paused (or when the timing is unusable). The timeline seeds its FIRST frame from this, never from the
 * stored position, so a tile composed fresh does not draw a stale bar for one frame.
 */
internal fun companionMediaTimelinePositionNow(track: CompanionMediaTrack, nowElapsedRealtimeMs: Long): Long =
    companionMediaProjectedPosition(
        positionMs = track.positionMs,
        durationMs = track.durationMs,
        playbackState = track.playbackState,
        positionUpdateElapsedRealtimeMs = track.positionUpdateElapsedRealtimeMs,
        playbackSpeed = track.playbackSpeed,
        nowElapsedRealtimeMs = nowElapsedRealtimeMs,
    ) ?: track.positionMs ?: 0L

@Composable
private fun MediaTimeline(
    track: CompanionMediaTrack,
    navigationEnabled: Boolean,
    seekGate: CompanionSeekGestureGate,
    onSeek: (Long, MediaSession.Token, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalMimirPalette.current
    val timing = companionMediaTimingSnapshot(track.positionMs, track.durationMs)
    val duration = timing?.durationMs ?: 1L
    // The FIRST frame already shows where the track is NOW: the stored callback position projected on the elapsed-realtime clock. The tile is composed
    // fresh each time the Activity pane (re)enters the Bottom's strip, and seeding from the stored position drew the last callback's bar for one frame
    // before the effect below corrected it.
    val position = companionMediaTimelinePositionNow(track, SystemClock.elapsedRealtime())
    var previewPosition by remember(track.sessionIdentity, track.presentationTrackIdentity) { mutableStateOf(position) }
    var livePosition by remember(track.sessionIdentity, track.presentationTrackIdentity) { mutableStateOf(position) }
    val latestEnabled by rememberUpdatedState(navigationEnabled)
    val latestOnSeek by rememberUpdatedState(onSeek)
    val latestPosition by rememberUpdatedState(livePosition)
    val latestTrack by rememberUpdatedState(track)
    LaunchedEffect(navigationEnabled) {
        if (!navigationEnabled) {
            seekGate.finish()
            livePosition = companionMediaTimelinePositionNow(latestTrack, SystemClock.elapsedRealtime())
            previewPosition = livePosition
        }
    }
    DisposableEffect(track.sessionIdentity, track.presentationTrackIdentity, track.canSeek, navigationEnabled) {
        onDispose { seekGate.finish() }
    }
    LaunchedEffect(
        track.sessionIdentity,
        track.presentationTrackIdentity,
        track.positionMs,
        track.durationMs,
        track.playbackState,
        track.positionUpdateElapsedRealtimeMs,
        track.playbackSpeed,
    ) {
        val callbackPosition = companionMediaTimelinePositionNow(track, SystemClock.elapsedRealtime())
        livePosition = callbackPosition
        if (!seekGate.active) previewPosition = callbackPosition
    }
    // D24: the 250 ms ticker runs only while this pane is the shown or dragged one. A parked pane keeps its composition and waits for no timer; the effect
    // is keyed on the pane's flag, so the drag that un-parks it starts the loop again, whose first pass writes the position projected to that moment.
    LaunchedEffectWhileLive(
        track.sessionIdentity,
        track.presentationTrackIdentity,
        track.durationMs,
        track.playbackState,
        navigationEnabled,
    ) {
        if (!navigationEnabled || timing == null || track.playbackState != CompanionMediaPlaybackState.PLAYING) {
            return@LaunchedEffectWhileLive
        }
        while (true) {
            val current = latestTrack
            val projected = companionMediaProjectedPosition(
                positionMs = current.positionMs,
                durationMs = current.durationMs,
                playbackState = current.playbackState,
                positionUpdateElapsedRealtimeMs = current.positionUpdateElapsedRealtimeMs,
                playbackSpeed = current.playbackSpeed,
                nowElapsedRealtimeMs = SystemClock.elapsedRealtime(),
            )
            if (projected != null) {
                livePosition = projected
                if (!seekGate.active) {
                    previewPosition = projected
                }
            }
            delay(250L)
        }
    }
    val timelineModifier = if (track.canSeek && timing != null) {
        Modifier.pointerInput(track.sessionIdentity, track.presentationTrackIdentity, duration, navigationEnabled) {
            // F13 (Thor re-check on combined16): the one slider grammar, [detectSliderTouch]. A tap seeks to the tapped fraction, a drag along the track
            // previews under the finger and seeks once on the release, and a swipe across the track seeks nothing. The DOWN is claimed in Initial, so the
            // outer Activity page detector cannot start a swipe from the lane.
            var engaged = false
            try {
                detectSliderTouch(
                    onEngaged = {
                        engaged = true
                        seekGate.begin()
                    },
                    emitAt = { x -> previewPosition = seekTrackPositionAt(x, size.width, duration) },
                    onEnd = {
                        if (engaged && latestEnabled && seekGate.active) {
                            val currentTrack = latestTrack
                            latestOnSeek(previewPosition, currentTrack.sessionIdentity, currentTrack.trackIdentity)
                        }
                        engaged = false
                        previewPosition = latestPosition
                        seekGate.finish()
                    },
                    pass = PointerEventPass.Initial,
                    claimDown = true,
                    enabled = { latestEnabled },
                )
            } finally {
                previewPosition = latestPosition
                if (seekGate.active) seekGate.finish()
            }
        }
    } else {
        Modifier
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MimirChromeText(
            if (timing == null) "--:--" else companionMediaFormatTime(previewPosition),
            style = MimirTypeScale.SupportCompact,
            color = palette.textSecondary,
        )
        val fraction = (previewPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .then(timelineModifier)
                // Its own node in the merged tree: the media tile around it is one pressable (W2d), and a pressable merges the semantics of what it holds,
                // which would fold the seek lane's progress and set-progress action into the tile. Nothing below it carries semantics, so nothing else changes.
                .semantics(mergeDescendants = true) {
                    contentDescription = when {
                        timing == null -> "Playback timing unavailable"
                        track.canSeek -> "Seek playback"
                        else -> "Playback progress"
                    }
                    if (timing != null) progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                    if (track.canSeek && timing != null) {
                        setProgress { target ->
                            if (!latestEnabled) return@setProgress false
                            val bounded = companionMediaSeekPosition(
                                (target.coerceIn(0f, 1f) * duration.toFloat()).toLong(),
                                duration,
                                canSeek = true,
                            ) ?: return@setProgress false
                            val currentTrack = latestTrack
                            latestOnSeek(bounded, currentTrack.sessionIdentity, currentTrack.trackIdentity)
                            previewPosition = latestPosition
                            true
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(palette.textMuted.copy(alpha = 0.28f)),
                ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxSize()
                        .background(palette.accent),
                )
                }
                if (track.canSeek && timing != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .offset(x = ((maxWidth - 12.dp).coerceAtLeast(0.dp) * fraction))
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(palette.accentBright),
                    )
                }
        }
        MimirChromeText(
            if (timing == null) "--:--" else companionMediaFormatTime(duration),
            style = MimirTypeScale.SupportCompact,
            color = palette.textSecondary,
        )
    }
}

private fun CompanionMediaPlaybackState.displayLabel(): String = when (this) {
    CompanionMediaPlaybackState.PLAYING -> "Playing"
    CompanionMediaPlaybackState.PAUSED -> "Paused"
    CompanionMediaPlaybackState.BUFFERING -> "Buffering"
    CompanionMediaPlaybackState.CONNECTING -> "Connecting"
    CompanionMediaPlaybackState.SKIPPING -> "Switching"
    CompanionMediaPlaybackState.STOPPED -> "Stopped"
    CompanionMediaPlaybackState.UNAVAILABLE -> "Unavailable"
}

@Composable
private fun RecentAppIcon(
    entry: CompanionAppEntry,
    iconSize: androidx.compose.ui.unit.Dp = 26.dp,
) {
    if (entry.iconPath != null) {
        // The lattice's own icon cache and off-main decode: an icon any Bottom site has decoded paints whole on the first frame, and Coil's per-composition
        // load (blank, then pop) is gone. Nothing is drawn while it loads or after it fails (R3, R8); a COLD first show fades in on FocusMs (Q13).
        CompanionAppIconArt(entry.iconPath, Modifier.size(iconSize))
    } else {
        Text("▣", color = LocalMimirPalette.current.accentBright)
    }
}
