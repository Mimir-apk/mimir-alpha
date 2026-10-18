package com.gruning.mimir.rewrite

import android.animation.ValueAnimator
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gruning.mimir.rewrite.core.audio.EqualizerPresetId
import com.gruning.mimir.rewrite.core.audio.EqualizerSettings
import com.gruning.mimir.rewrite.core.audio.MimirEqualizer
import com.gruning.mimir.rewrite.core.audio.MimirMusicControl
import com.gruning.mimir.rewrite.core.audio.MimirMusicLibrary
import com.gruning.mimir.rewrite.core.audio.MusicControlState
import com.gruning.mimir.rewrite.core.audio.MusicLibraryView
import com.gruning.mimir.rewrite.core.audio.MusicSource
import com.gruning.mimir.rewrite.core.audio.SystemEqualizerState
import com.gruning.mimir.rewrite.core.companionnative.MusicPanelEdgeClearanceDp
import com.gruning.mimir.rewrite.core.companionnative.OverlayButtonBounds
import com.gruning.mimir.rewrite.core.companionnative.musicPanelPlacement
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.MimirNavFeedback
import com.gruning.mimir.rewrite.core.input.mimirActivatable
import com.gruning.mimir.rewrite.core.input.mimirFocusable
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MediaArtFallbackGlyph
import com.gruning.mimir.rewrite.core.ui.MimirBeforeFirstValidViewport
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirFilledControlRingReach
import com.gruning.mimir.rewrite.core.ui.MimirFocusFlareReach
import com.gruning.mimir.rewrite.core.ui.MimirFocusTileRingReach
import com.gruning.mimir.rewrite.core.ui.MimirGlassScrim
import com.gruning.mimir.rewrite.core.ui.MimirIntentText
import com.gruning.mimir.rewrite.core.ui.MimirLiquidGlassPanel
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirSwapPageGate
import com.gruning.mimir.rewrite.core.ui.MimirTextIntent
import com.gruning.mimir.rewrite.core.ui.mimirPressResponse
import com.gruning.mimir.rewrite.core.ui.mimirReducedMotion
import com.gruning.mimir.rewrite.core.ui.mimirRingSafeFade
import com.gruning.mimir.rewrite.core.ui.mimirRingSafeFadeAlpha
import com.gruning.mimir.rewrite.core.ui.mimirSwapFadeAlpha
import com.gruning.mimir.rewrite.core.ui.mimirSwapTransform
import com.gruning.mimir.rewrite.core.ui.MimirRingShape
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.liquidGlassContentPalette
import com.gruning.mimir.rewrite.core.ui.mimirSelectionRing
import com.gruning.mimir.rewrite.core.ui.mimirGlassPanelEntry
import com.gruning.mimir.rewrite.core.ui.mimirMeasuredViewport
import com.gruning.mimir.rewrite.core.ui.rememberMeasuredViewport
import com.gruning.mimir.rewrite.core.ui.rememberMimirGlassProgress
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** The card's width: 320 dp, but never wider than the display less the clearance on both sides. */
internal const val MusicPanelWidthDp = 320

/**
 * The art seat's side for Your music on Main's card, smaller than the other player's 96 dp: Main is 468 dp tall and the card is already 439 dp with a 96 dp seat, so a raised
 * font size would scroll the card and cut the art's top. The Bottom's player and tile pass their own sizes.
 */
internal const val MusicPanelYoursArtSizeDp = 72

/** The card, for the layout harness. A descendant of the measured-viewport seat, so it is never the node that is held. */
internal const val MusicPanelCardTestTag = "music/panel/card"

/** What the panel reads and drives. [MimirMusicControl] in production; a fake in tests. */
internal interface MusicPanelEngine {
    val state: StateFlow<MusicControlState>
    val musicVolume: StateFlow<Float>
    val mediaVolume: StateFlow<Float>
    val cappedByMedia: StateFlow<Boolean>

    /** Your music's equalizer: the panel offers its preset row only if it was on when the panel opened. */
    val equalizer: StateFlow<EqualizerSettings>

    /** What the global engine honestly reports: the Equalizer row's line says it reaches all sound only while the engine has the mix. An engine with none (a test's) reads as not answered yet. */
    val systemEqualizer: StateFlow<SystemEqualizerState> get() = IdleSystemEqualizer

    /** Play or pause [source]'s player: the card the player pressed, never the other one. */
    fun playPause(source: MusicSource)
    fun next(source: MusicSource)
    fun previous(source: MusicSource)
    fun setMusicVolume(fraction: Float)
    fun setMediaVolume(fraction: Float)
    fun applyEqualizerPreset(id: EqualizerPresetId)

    /** Opens the other player's app, growing out of [bounds] in [view]; false when it could not be opened (the panel then stays and says so). */
    fun openOtherPlayer(view: View?, bounds: Rect?): Boolean

    /** Moves Your music's track on air to [positionMs] (the player's seek bar, W3e). Another player's seek goes through its own session, so the default does nothing. */
    fun seekYourMusic(positionMs: Long) = Unit

    /** The library as the Artist / Album browser draws it. An engine with no library (a test's) has an empty one, and the panel offers no browser. */
    val library: StateFlow<MusicLibraryView> get() = EmptyMusicLibrary

    /** The player pressed [trackId] in the browser: its album plays from that track. */
    fun playFromLibrary(trackId: String) = Unit

    /** The player pressed "All music" in the browser: the pick is cleared and the whole folder plays, in general playback's own order. */
    fun playAll() = Unit
}

private val EmptyMusicLibrary: StateFlow<MusicLibraryView> = MutableStateFlow(MusicLibraryView())

private val IdleSystemEqualizer: StateFlow<SystemEqualizerState> = MutableStateFlow(SystemEqualizerState())

internal object MimirMusicPanelEngine : MusicPanelEngine {
    override val state: StateFlow<MusicControlState> get() = MimirMusicControl.state
    override val musicVolume: StateFlow<Float> get() = MimirMusicControl.musicVolume
    override val mediaVolume: StateFlow<Float> get() = MimirMusicControl.mediaVolume
    override val cappedByMedia: StateFlow<Boolean> get() = MimirMusicControl.musicCappedByMedia
    override val equalizer: StateFlow<EqualizerSettings> get() = MimirEqualizer.settings
    override val systemEqualizer: StateFlow<SystemEqualizerState> get() = MimirEqualizer.systemEqualizer

    override fun playPause(source: MusicSource) {
        MimirMusicControl.playPause(target = source)
    }

    override fun next(source: MusicSource) {
        MimirMusicControl.next(target = source)
    }

    override fun previous(source: MusicSource) {
        MimirMusicControl.previous(target = source)
    }

    override fun setMusicVolume(fraction: Float) {
        MimirMusicControl.setMusicVolume(fraction)
    }

    override fun setMediaVolume(fraction: Float) {
        MimirMusicControl.setMediaVolume(fraction)
    }

    override fun applyEqualizerPreset(id: EqualizerPresetId) = MimirEqualizer.applyPreset(id)

    override fun openOtherPlayer(view: View?, bounds: Rect?): Boolean = CompanionMediaSessionBridge.openSelectedPlayer(view, bounds)

    override fun seekYourMusic(positionMs: Long) = MimirMusicControl.seekYourMusic(positionMs)

    override val library: StateFlow<MusicLibraryView> get() = MimirMusicLibrary.view

    override fun playFromLibrary(trackId: String) {
        MimirMusicLibrary.playFromLibrary(trackId)
        // A press on a track is a play: music the player had paused (or a hold they can lift) starts on the track they chose.
        if (MimirMusicControl.state.value.yours?.isPlaying == false) MimirMusicControl.playPause(target = MusicSource.YOUR_MUSIC)
    }

    override fun playAll() {
        MimirMusicLibrary.clearSelection()
        // "All music" is a play too: music the player had paused starts on the whole folder. With no pick active and music already playing, nothing changes.
        if (MimirMusicControl.state.value.yours?.isPlaying == false) MimirMusicControl.playPause(target = MusicSource.YOUR_MUSIC)
    }
}

/**
 * One music panel's state and input. Created by [PrimaryMButtonPanelHost] with the panel's own [authority] and scope, so it owns
 * no process-lifetime state. [model] is what the cards show; touch calls the `on...` methods directly (a tap acts at once) and the
 * controller goes through [dispatch] and [onKey] (A acts on release, [MusicPanelKeyGate]).
 *
 * **One card and a switch row.** When another player's session and Your music are both there, the panel shows ONE full card (the
 * playing one, else the most recently played, [musicPanelFullCard]) and the other source as a compact switch row under it; a press on
 * the row plays that source, which pauses the current one (the engine's one-player-sounds rule), and the card follows the press. The
 * choice is decided when the panel opens, when a source arrives or leaves, and by a press on the row: the card never swaps under a
 * finger or a ring because a player paused.
 *
 * **Playback changes never close the panel.** When nothing is left to control (Your music turned off, the sessions ended) it keeps
 * the last thing it showed (the top card) with the transport disabled and "Stopped" in the status line, so the Music slider stays
 * reachable. A card whose player is gone while the other remains is simply dropped.
 *
 * **The cap.** The Music thumb stops at the Game & media level. Pushing past it (a drag, or a step at the stop) raises [capPushed],
 * which shows the one cap line until the finger lifts or the ring moves; it never raises the stored level past what Game & media
 * can play, and never lowers one already above it, so the player's level is there again if Game & media comes back up. A level of
 * zero is Your music's off switch, so Game & media at zero never drags Music to zero.
 */
internal class MusicPanelController(
    private val engine: MusicPanelEngine,
    val authority: FocusAuthority,
    private val scope: CoroutineScope,
    private val mediaVolume: MediaVolumeGateway,
    val anchor: OverlayButtonBounds?,
    private val onClose: () -> Unit,
    /** The other player's app could not be opened from the art (the panel stays up); the host says so. */
    private val onOpenFailed: () -> Unit = {},
    /** Uptime, injectable for tests. */
    private val now: () -> Long = { SystemClock.uptimeMillis() },
    /** How long a switch holds the next one off: the swap's whole clock ([musicPanelSwapSettleMs]); 0 under reduced motion. */
    private val swapSettleMs: Long = musicPanelSwapSettleMs(),
) {
    private val mediaAvailable = runCatching { mediaVolume.isAvailable() }.getOrDefault(false)
    private val mediaFixed = runCatching { mediaVolume.isFixedVolume() }.getOrDefault(true)
    private var held: MusicControlState = engine.state.value
    private var stopped = !engine.state.value.present
    private var capPushed = false
    private var swapLockedUntil = 0L

    /** A finger is down on the panel: a follow of the playing source waits for it to lift, so the card never moves under it. */
    private var touchDown = false

    /** After a press on the switch row the engine takes a moment to catch up: until then the old state must not pull the card back. */
    private var followHoldUntil = 0L
    private var followRetry: Job? = null

    /** The one collection of the engine's five flows. Held so a host that parks the panel (D24) can stop it; Main never does. Declared before `init`, which starts it. */
    private var feed: Job? = null

    /** The equalizer was on when the panel opened: its preset row is offered for the panel's life, whatever happens to the switch. */
    private val equalizerShown = engine.equalizer.value.enabled

    /** Where the other player's artwork is, for the window that grows out of it. Plain fields: layout fills them, nothing observes them. */
    private var artView: View? = null
    private var artBounds: Rect? = null

    /** Which sources the held full card was decided for, and the card: re-decided only when a source arrives or leaves, or on a switch. */
    private var orderedFor: Pair<Boolean, Boolean>? = null
    private var fullCard = MusicPanelCard.YOURS

    /** The library the browser shows and where the browser is. [browsing] is whether it is open; its position is kept while it is closed, so it opens where it was left. */
    private var library: MusicLibraryView = engine.library.value
    private var browseState = MusicBrowseState().resolved(library.tree)
    private var browsing = false
    private var browseMemo: BrowseMemo? = null

    val keyGate = MusicPanelKeyGate(onConfirm = ::activateFocused, onCancel = ::cancelPressed)

    var model by mutableStateOf(buildModel())
        private set

    init {
        authority.rememberFocusForScope(
            MusicPanelScope,
            musicPanelFocusKey(musicPanelInitialControl(model) ?: MusicPanelControl.YOURS_PLAY_PAUSE),
        )
        authority.pushScope(MusicPanelScope)
        startFeed()
    }


    private fun startFeed() {
        if (feed?.isActive == true) return
        feed = scope.launch {
            combine(
                combine(engine.state, engine.musicVolume, engine.mediaVolume, engine.cappedByMedia, engine.equalizer) { _, _, _, _, _ -> Unit },
                combine(engine.library, engine.systemEqualizer) { _, _ -> Unit },
            ) { _, _ -> Unit }
                .collect { refresh() }
        }
    }

    /**
     * D24: a Bottom host that PARKS this panel (the player is open and the workspace swipes away from it) calls this with false: the engine's
     * flows are no longer collected and the follow's retry timer is cancelled, so a hidden panel runs nothing. With true the collection starts again
     * and its first pass rebuilds the model from the engine's CURRENT state, so the panel is current the moment it comes back. A host that never
     * calls it (Main) gets the always-live panel it had.
     */
    fun setLive(live: Boolean) {
        if (live) {
            startFeed()
        } else {
            feed?.cancel()
            feed = null
            followRetry?.cancel()
            followRetry = null
        }
    }

    private fun buildModel(): MusicPanelModel {
        val now = engine.state.value
        if (now.present) {
            held = now
            stopped = false
            val cardsNow = (now.yours != null) to (now.other != null)
            if (cardsNow != orderedFor) {
                orderedFor = cardsNow
                fullCard = musicPanelFullCard(now)
            } else {
                followPlayback(now)
            }
        } else {
            stopped = true
        }
        // The browser follows the library: a rescan or a tag re-sorts it under the cursor, which stays on its row by key.
        val latest = engine.library.value
        if (latest !== library) {
            library = latest
            browseState = browseState.resolved(latest.tree)
        }
        val built = musicPanelModel(
            control = held,
            stopped = stopped,
            musicVolume = engine.musicVolume.value,
            mediaVolume = engine.mediaVolume.value,
            mediaAvailable = mediaAvailable,
            mediaFixed = mediaFixed,
            cappedByMedia = engine.cappedByMedia.value,
            capPushed = capPushed,
            fullCard = fullCard,
            equalizer = engine.equalizer.value,
            systemEq = engine.systemEqualizer.value,
            equalizerShown = equalizerShown,
            library = library,
            browse = browseUi(),
        )
        // A card that cannot offer the browser any more (the other player took the card, the library went) closes it.
        if (browsing && built.browse == null) browsing = false
        return built
    }

    private fun browseUi(): MusicBrowseUi? {
        if (!browsing) return null
        val playing = held.yours?.trackId
        browseMemo?.takeIf { it.library === library && it.state == browseState && it.nowPlayingId == playing }?.let { return it.ui }
        return musicBrowseUi(library.tree, browseState, playing, library.tagsRead, library.trackCount, library.pickedTrackId)
            .also { browseMemo = BrowseMemo(library, browseState, playing, it) }
    }

    private fun refresh() {
        model = buildModel()
    }

    private fun uptime(): Long = now()

    /**
     * The full card follows the playing source while the panel is open (YouTube Music started under a Your-music card and
     * the panel kept showing Your music). Only when exactly one source plays and it is not the card's ([musicPanelPlayingCard]): a tie (the
     * 2 s fade window when another player takes focus) holds the card, so it cannot flip twice. The card never moves under the player's
     * hand, so a follow that arrives with a finger or a key down, inside the swap that is still running, or inside the hold after a press
     * on the switch row waits, and is applied the moment that ends. The follow's own swap holds the next one off for its length.
     */
    private fun followPlayback(state: MusicControlState) {
        val target = musicPanelPlayingCard(state) ?: return
        if (target == fullCard) return
        val at = uptime()
        val waitMs = when {
            touchDown || keyGate.keyHeld -> -1L
            at < followHoldUntil -> followHoldUntil - at
            at < swapLockedUntil -> swapLockedUntil - at
            else -> 0L
        }
        if (waitMs == 0L) {
            fullCard = target
            swapLockedUntil = at + swapSettleMs
            return
        }
        // Released by the touch or key ending (both refresh); a timed wait is re-checked when it is over.
        if (waitMs > 0) {
            followRetry?.cancel()
            followRetry = scope.launch {
                delay(waitMs + 1)
                refresh()
            }
        }
    }

    /** A finger went down or lifted anywhere on the panel (the card reports it without consuming it). A lift applies a waiting follow. */
    fun onTouchActive(active: Boolean) {
        if (touchDown == active) return
        touchDown = active
        if (!active) refresh()
    }

    private fun focusedControl(): MusicPanelControl? = musicPanelControlFor(authority.state.value.focused)

    private fun setCapPushed(value: Boolean) {
        if (capPushed == value) return
        capPushed = value
        refresh()
    }

    // ---- touch and the controller's A: the same actions ----

    fun onPlayPause(card: MusicPanelCard) = engine.playPause(card.source)

    fun onPrevious(card: MusicPanelCard) = engine.previous(card.source)

    fun onNext(card: MusicPanelCard) = engine.next(card.source)

    fun onMediaRequested(fraction: Float) {
        if (mediaFixed) return
        engine.setMediaVolume(fraction)
    }

    /** The player asks for Music at [fraction] (a drag, or a controller step). See the cap rules in the class comment. */
    fun onMusicRequested(fraction: Float) {
        applyMusicRequest(fraction)
    }

    /** Returns whether the request was a move (false: it only pushed against the cap). */
    private fun applyMusicRequest(fraction: Float): Boolean {
        val requested = fraction.coerceIn(0f, 1f)
        val limit = if (mediaAvailable) engine.mediaVolume.value else 1f
        return if (requested > limit + CapEpsilon) {
            if (limit > 0f && engine.musicVolume.value < limit) engine.setMusicVolume(limit)
            setCapPushed(true)
            false
        } else {
            engine.setMusicVolume(requested)
            setCapPushed(false)
            true
        }
    }

    fun onMusicInteractionEnd() = setCapPushed(false)

    /**
     * A press on the switch row (A or a tap): plays the OTHER source, which pauses the current one (the engine's one-player-sounds
     * rule, so the panel, the notification and the media keys all agree). The full card follows the press at once; if that source
     * cannot start, its card shows it paused and the player can press again once the swap has settled ([swapSettleMs]). A source that is
     * already playing is only brought forward.
     */
    fun onSwitchSource() {
        val row = model.switchRow ?: return
        // One press, one switch: while the card is still changing, a second press (a double tap, A held on release) is not another switch.
        val at = now()
        if (at < swapLockedUntil) return
        swapLockedUntil = at + swapSettleMs
        followHoldUntil = at + MusicPanelFollowHoldMs
        fullCard = row.card
        if (!row.playing) engine.playPause(row.card.source)
        refresh()
    }

    // ---- the Artist / Album browser ----

    /** The Library chip (A or a tap): the browser opens in place of the card's body, where the player left it. Only while the card offers it. */
    fun onBrowse() {
        if (model.card(MusicPanelCard.YOURS)?.canBrowse != true || model.top.card != MusicPanelCard.YOURS) return
        browsing = true
        refresh()
    }

    /** The player leaves the browser for the card (the Bottom's sheet closing). */
    fun onBrowseExit() {
        if (!browsing) return
        browsing = false
        refresh()
    }

    /**
     * Back (B on Main, the Back chip on the Bottom): one level up, the cursor on the group the player came from; from the top, out of the browser. Returns whether it went
     * up a level (false: it left the browser, or there was none open).
     */
    fun onBrowseBack(): Boolean {
        if (!browsing) return false
        val up = browseState.back()
        if (up != null) {
            browseState = up
        } else {
            browsing = false
        }
        refresh()
        return up != null
    }

    /** A press (A, or a tap) on the row [key]: an artist opens its albums, an album its tracks, a track plays its album from there, "All music" plays the whole folder. */
    fun onBrowsePress(key: String?) {
        if (!browsing) return
        when (val action = browseState.pressed(library.tree, key, held.yours?.trackId)) {
            is MusicBrowseAction.Go -> browseState = action.state
            is MusicBrowseAction.Play -> {
                browseState = browseState.copy(selectedKey = action.trackId)
                engine.playFromLibrary(action.trackId)
            }
            MusicBrowseAction.PlayAll -> {
                browseState = browseState.copy(selectedKey = MusicBrowseAllKey)
                engine.playAll()
            }
            MusicBrowseAction.None -> return
        }
        refresh()
    }

    private fun moveBrowse(delta: Int) {
        val rows = browseUi()?.rows ?: return
        val moved = browseState.moved(rows, delta)
        if (moved == browseState) return
        browseState = moved
        MimirNavFeedback.consumed()
        refresh()
    }

    /** B: up a level in the browser, else the panel closes. */
    private fun cancelPressed() {
        if (browsing) onBrowseBack() else onClose()
    }

    /** A step of the Equalizer row: the preset [direction] along the cycle (it wraps, Custom last). Applied and stored at once. */
    fun onEqualizerStep(direction: Int): Boolean {
        val now = engine.equalizer.value.presetId
        val next = equalizerCyclePreset(now, direction)
        if (next == now) return false
        engine.applyEqualizerPreset(next)
        return true
    }

    /** The other player's artwork has been laid out: where the app it opens grows out of. */
    fun onArtPlaced(view: View, bounds: Rect) {
        artView = view
        artBounds = bounds
    }

    /**
     * A tap on the other player's art, or A on it: that player's app opens, growing out of the art, and the panel detaches in the same
     * frame ([onClose] posts the one instant exit). Back from that app returns to what was underneath. Your music's art has no
     * such control. When it cannot be opened the panel stays and the host says so.
     */
    fun onOpenOtherPlayer() {
        if (model.card(MusicPanelCard.OTHER)?.canOpenPlayer != true) return
        if (engine.openOtherPlayer(artView, artBounds)) onClose() else onOpenFailed()
    }

    fun requestFocus(control: MusicPanelControl) {
        if (focusedControl() != control) setCapPushed(false)
        authority.requestFocus(musicPanelFocusKey(control))
    }

    private fun activateFocused() {
        val focused = focusedControl() ?: return
        if (focused == MusicPanelControl.BROWSE_LIST) {
            onBrowsePress(browseState.selectedKey)
            return
        }
        if (focused == MusicPanelControl.BROWSE) {
            onBrowse()
            return
        }
        if (focused == MusicPanelControl.OTHER_ART) {
            onOpenOtherPlayer()
            return
        }
        if (focused == MusicPanelControl.SWITCH) {
            onSwitchSource()
            return
        }
        val card = focused.card ?: return
        val shown = model.card(card) ?: return
        val transport = focused.transport ?: return
        if (!shown.canDo(transport)) return
        when (transport) {
            MusicPanelTransport.PREVIOUS -> onPrevious(card)
            MusicPanelTransport.PLAY_PAUSE -> onPlayPause(card)
            MusicPanelTransport.NEXT -> onNext(card)
        }
    }

    // ---- the controller ----

    /** The router's handler. Every normalized action is consumed. */
    fun dispatch(action: ControllerAction): Boolean {
        when (action) {
            ControllerAction.CONFIRM, ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> keyGate.arm(action)
            ControllerAction.NAV_LEFT, ControllerAction.NAV_RIGHT -> {
                val direction = if (action == ControllerAction.NAV_LEFT) -1 else 1
                when (val focused = focusedControl()) {
                    MusicPanelControl.MEDIA_VOLUME -> stepMedia(direction)
                    MusicPanelControl.MUSIC_VOLUME -> stepMusic(direction)
                    MusicPanelControl.EQUALIZER -> if (onEqualizerStep(direction)) MimirNavFeedback.consumed()
                    else -> move(focused, action)
                }
            }
            ControllerAction.NAV_UP, ControllerAction.NAV_DOWN ->
                if (browsing) moveBrowse(if (action == ControllerAction.NAV_UP) -1 else 1) else move(focusedControl(), action)
            else -> Unit
        }
        return true
    }

    /** The ComposeView's key listener: A and B act on release, volume keys go to Android. */
    fun onKey(keyCode: Int, down: Boolean, repeatCount: Int, route: () -> Boolean): Boolean {
        val consumed = keyGate.onKey(keyCode, down, repeatCount, route)
        // A key lifting applies a follow that waited for it.
        if (!down) refresh()
        return consumed
    }

    private fun move(from: MusicPanelControl?, action: ControllerAction) {
        val target = musicPanelNeighbor(model, from, action) ?: return
        requestFocus(target)
    }

    private fun stepMusic(direction: Int) {
        // A step the router cannot see in the focus snapshot still ticks, as Utility's value steps do (MimirNavFeedback).
        if (applyMusicRequest(BrightnessMath.step(BrightnessTarget.AMBIENT_SOUND, model.musicFraction, direction))) {
            MimirNavFeedback.consumed()
        }
    }

    private fun stepMedia(direction: Int) {
        if (mediaFixed) return
        // One system step per press, not a 5 % fraction: with 15 steps a 5 % move can round back onto the step it left.
        if (runCatching { mediaVolume.step(direction) }.getOrNull() != null) MimirNavFeedback.consumed()
    }

    fun dispose() {
        followRetry?.cancel()
        keyGate.reset()
        authority.discardScope(MusicPanelScope)
    }

    private companion object {
        const val CapEpsilon = 0.004f
    }
}

/** What the browser's rows were last built from, so a refresh that changed none of it (a slider tick) does not rebuild thousands of rows. */
private class BrowseMemo(val library: MusicLibraryView, val state: MusicBrowseState, val nowPlayingId: String?, val ui: MusicBrowseUi)

/** How long after a press on the switch row the engine's old state is not allowed to pull the card back (the other player's pause and Your music's resume take a moment). */
internal const val MusicPanelFollowHoldMs = 3_000L

/** The card swap's whole clock: the departing fade's lead plus the arrival ([MimirMotion.OverlayDissolveCoverMs] + [MimirMotion.OverlayMs]); none under reduced motion. */
internal fun musicPanelSwapSettleMs(): Long =
    if (ValueAnimator.areAnimatorsEnabled()) (MimirMotion.OverlayDissolveCoverMs + MimirMotion.OverlayMs).toLong() else 0L

/** The stateful card: collects nothing itself, [MusicPanelController.model] is Compose state. */
@Composable
internal fun MusicPanelHost(controller: MusicPanelController, onDismiss: () -> Unit) {
    // Your music's art is laid over the card here, not in the controller: the model is the engine's state, and a picture is a surface's own ask .
    val yoursArt = rememberYourMusicArt(controller.model.card(MusicPanelCard.YOURS)?.trackId, MusicPanelYoursArtSizeDp.dp)
    val model = controller.model.withYoursArt(yoursArt)
    val focus by controller.authority.state.collectAsState()
    MusicPanelOverlay(
        // The window host draws the scrim: it must outlast the card's fade and clear after it ([NativePanelGlassExitFade]).
        scrimDrawnByHost = true,
        model = model,
        focused = musicPanelControlFor(focus.focused),
        authority = controller.authority,
        anchor = controller.anchor,
        onDismiss = onDismiss,
        onPlayPause = controller::onPlayPause,
        onPrevious = controller::onPrevious,
        onNext = controller::onNext,
        onMediaRequested = controller::onMediaRequested,
        onMusicRequested = controller::onMusicRequested,
        onMusicInteractionEnd = controller::onMusicInteractionEnd,
        onTouchActive = controller::onTouchActive,
        onRequestFocus = controller::requestFocus,
        onEqualizerStep = { controller.onEqualizerStep(it) },
        onOpenOtherPlayer = controller::onOpenOtherPlayer,
        onSwitchSource = controller::onSwitchSource,
        onArtPlaced = controller::onArtPlaced,
        browse = MusicBrowseHooks(onOpen = controller::onBrowse, onPress = controller::onBrowsePress, onBack = { controller.onBrowseBack() }),
    )
}

/**
 * What the Artist / Album browser's controls call. [onOpen] null offers no Library chip (a host that has no browser); the rest are the browser's own presses.
 */
internal data class MusicBrowseHooks(
    val onOpen: (() -> Unit)? = null,
    val onPress: (String) -> Unit = {},
    val onBack: () -> Unit = {},
)

/**
 * The single-display music panel: a glass card over the shared scrim, anchored to the M badge like the M menu, on the menu's
 * entry tier (180 ms, [MimirMotion.Easing], the 0.96 to 1 lift, the scrim on the same clock). It leaves on the same clock, and
 * the panel itself draws nothing of it: the host ([PrimaryMButtonPanelHost]) fades the whole window body out on
 * [MimirMotion.OverlayMs] with input dead and detaches it when the fade ends ([NativePanelExitFade]).
 *
 * The glass behind the card, the scrim and the game's window blur (Q14, `FLAG_BLUR_BEHIND` where Android blurs across
 * windows), is the window host's, not this panel's ([scrimDrawnByHost], [NativePanelGlassExitFade]): on the way out the card dissolves
 * first and the glass holds a beat and then clears, and the window goes when the glass is gone.
 * Where the platform will not blur, the scrim over a sharp game is the panel, as it was.
 *
 * Stateless, so the layout harness drives it with a plain [model]. [authority] null draws no focus registration (tests). A measured
 * zero viewport (a live font-scale or display change) holds the last valid size and draws nothing for that frame
 * ([rememberMeasuredViewport], D-16), so a transient zero neither crashes nor moves the card.
 */
@Composable
internal fun MusicPanelOverlay(
    model: MusicPanelModel,
    focused: MusicPanelControl?,
    authority: FocusAuthority?,
    anchor: OverlayButtonBounds?,
    onDismiss: () -> Unit,
    onPlayPause: (MusicPanelCard) -> Unit,
    onPrevious: (MusicPanelCard) -> Unit,
    onNext: (MusicPanelCard) -> Unit,
    onMediaRequested: (Float) -> Unit,
    onMusicRequested: (Float) -> Unit,
    onMusicInteractionEnd: () -> Unit,
    onRequestFocus: (MusicPanelControl) -> Unit,
    onTouchActive: (Boolean) -> Unit = {},
    onEqualizerStep: (Int) -> Unit = {},
    onOpenOtherPlayer: () -> Unit = {},
    onSwitchSource: () -> Unit = {},
    onArtPlaced: (View, Rect) -> Unit = { _, _ -> },
    browse: MusicBrowseHooks = MusicBrowseHooks(),
    /** True when the window host draws the scrim (and the blur behind it) outside this panel's fade; false draws it here, as the layout harnesses do. */
    scrimDrawnByHost: Boolean = false,
) {
    val palette = LocalMimirPalette.current.liquidGlassContentPalette()
    val entry = rememberMimirGlassProgress(visible = true)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val measuredWidth = maxWidth
        val measuredHeight = maxHeight
        if (!measuredWidth.value.isFinite() || !measuredHeight.value.isFinite()) return@BoxWithConstraints
        val viewport = rememberMeasuredViewport(
            maxWidth = measuredWidth,
            maxHeight = measuredHeight,
            inDomain = { widthDp, heightDp -> widthDp.isFinite() && heightDp.isFinite() && widthDp > 0f && heightDp > 0f },
            beforeFirstValid = MimirBeforeFirstValidViewport.RENDER_NOTHING,
        )
        if (!viewport.shouldRender) return@BoxWithConstraints
        // No catch-all pointer handler here: the panel is its own full-screen window, so nothing under it can be touched, and a
        // handler that consumes every event after its children cancels every drifting tap (detectTapGestures checks the Final
        // pass of each move). The backdrop below is the full-size tap target.
        Box(Modifier.mimirMeasuredViewport(viewport)) {
            if (!scrimDrawnByHost) MimirGlassScrim(progress = entry)
            Box(
                Modifier
                    .fillMaxSize()
                    // A tap that misses the card closes the panel and is not passed to the game. A pointer handler, not clickable():
                    // clickable adds a Compose focus target and the one FocusAuthority owns focus here.
                    .pointerInput(onDismiss) { detectTapGestures(onTap = { onDismiss() }) },
            )
            val density = LocalDensity.current
            val insetPx = with(density) { MusicPanelEdgeClearanceDp.dp.roundToPx() }
            val widthCapPx = with(density) { MusicPanelWidthDp.dp.roundToPx() }
            Layout(
                modifier = Modifier.fillMaxSize(),
                content = {
                    MusicPanelCard(
                        model = model,
                        focused = focused,
                        authority = authority,
                        palette = palette,
                        entry = entry,
                        onPlayPause = onPlayPause,
                        onPrevious = onPrevious,
                        onNext = onNext,
                        onMediaRequested = onMediaRequested,
                        onMusicRequested = onMusicRequested,
                        onMusicInteractionEnd = onMusicInteractionEnd,
                        onRequestFocus = onRequestFocus,
                        onTouchActive = onTouchActive,
                        onEqualizerStep = onEqualizerStep,
                        onOpenOtherPlayer = onOpenOtherPlayer,
                        onSwitchSource = onSwitchSource,
                        onArtPlaced = onArtPlaced,
                        browse = browse,
                    )
                },
            ) { measurables, constraints ->
                val windowWidth = constraints.maxWidth
                val windowHeight = constraints.maxHeight
                val cardWidth = minOf(widthCapPx, windowWidth - 2 * insetPx).coerceAtLeast(0)
                val cardMaxHeight = (windowHeight - 2 * insetPx).coerceAtLeast(0)
                val card = measurables.single().measure(Constraints(cardWidth, cardWidth, 0, cardMaxHeight))
                layout(windowWidth, windowHeight) {
                    val at = musicPanelPlacement(anchor, windowWidth, windowHeight, card.width, card.height, insetPx)
                    card.place(at.leftPx, at.topPx)
                }
            }
        }
    }
}

@Composable
private fun MusicPanelCard(
    model: MusicPanelModel,
    focused: MusicPanelControl?,
    authority: FocusAuthority?,
    palette: MimirThemePalette,
    entry: () -> Float,
    onPlayPause: (MusicPanelCard) -> Unit,
    onPrevious: (MusicPanelCard) -> Unit,
    onNext: (MusicPanelCard) -> Unit,
    onMediaRequested: (Float) -> Unit,
    onMusicRequested: (Float) -> Unit,
    onMusicInteractionEnd: () -> Unit,
    onRequestFocus: (MusicPanelControl) -> Unit,
    onTouchActive: (Boolean) -> Unit,
    onEqualizerStep: (Int) -> Unit,
    onOpenOtherPlayer: () -> Unit,
    onSwitchSource: () -> Unit,
    onArtPlaced: (View, Rect) -> Unit,
    browse: MusicBrowseHooks,
) {
    val scroll = rememberScrollState()
    var viewportHeightPx by remember { mutableIntStateOf(0) }
    val geometry = remember { mutableStateMapOf<MusicPanelControl, UtilityRailItemGeometry>() }
    // Row geometry is read as the row's position INSIDE the viewport, not as two window positions: the card scales 0.96 to 1 on its
    // entry, and a window position carries that scale, so every row's top changed each frame of the entry and restarted the scroll
    // effect below. Two nodes under the same scaled ancestor have the scale cancel in localPositionOf. Plain holders, not state: the
    // layout callbacks fill them and the callbacks (either one first) publish the result into [geometry].
    val viewportCoords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val rowCoords = remember { mutableMapOf<MusicPanelControl, LayoutCoordinates>() }
    fun remeasureRows() {
        val viewport = viewportCoords[0]?.takeIf { it.isAttached && it.size.height > 0 } ?: return
        rowCoords.entries.forEach { (control, row) ->
            if (!row.isAttached || row.size.height <= 0) return@forEach
            val measured = UtilityRailItemGeometry(
                topPx = viewport.localPositionOf(row, Offset.Zero).y.roundToInt() + scroll.value,
                heightPx = row.size.height,
            )
            if (geometry[control] != measured) geometry[control] = measured
        }
    }
    val order = remember(model) { musicPanelFocusRows(model).flatten() }
    val focusedGeometry = focused?.let(geometry::get)
    // The card scrolls only when it is taller than the display allows; then the focused control carries the card the way
    // Utility's rail does (its first control reveals the top, its last the bottom, the rest settle on the centre).
    LaunchedEffect(focused, focusedGeometry, viewportHeightPx, scroll.maxValue, order) {
        val reveal = when {
            focused == null || focused !in order -> UtilityRailReveal.NONE
            focused == order.first() -> UtilityRailReveal.START
            focused == order.last() -> UtilityRailReveal.END
            else -> UtilityRailReveal.ROW
        }
        utilityRailSettlementTarget(reveal, focusedGeometry, viewportHeightPx, scroll.maxValue)?.let { target ->
            if (scroll.maxValue > 0) scroll.animateScrollTo(target)
        }
    }
    // First frame beat, then the ring: a key only takes focus once it has laid out and registered.
    if (authority != null) {
        LaunchedEffect(authority, order) {
            withFrameNanos { }
            val current = musicPanelControlFor(authority.state.value.focused)
            if (current == null || current !in order) {
                // Back out of the browser lands on the Library chip it was opened from; anything else starts at the card's first control.
                val target = if (current == MusicPanelControl.BROWSE_LIST && MusicPanelControl.BROWSE in order) MusicPanelControl.BROWSE else musicPanelInitialControl(model)
                target?.let { authority.requestFocus(musicPanelFocusKey(it)) }
            }
        }
    }

    @Composable
    fun control(control: MusicPanelControl, reachable: Boolean): Modifier {
        var modifier: Modifier = Modifier
        if (authority != null && reachable) {
            modifier = modifier.mimirFocusable(
                authority = authority,
                key = musicPanelFocusKey(control),
                scope = MusicPanelScope,
                autoBringIntoView = false,
            )
        }
        return modifier.onGloballyPositioned { coordinates ->
            rowCoords[control] = coordinates
            remeasureRows()
        }
    }

    MimirLiquidGlassPanel(
        modifier = Modifier
            .mimirGlassPanelEntry(entry)
            .testTag(MusicPanelCardTestTag)
            .pointerInput(onTouchActive) {
                // Keeps the card's bounds out of the backdrop's reach so a tap on it never closes the panel; never consumes. It also tells the
                // controller while a finger is down (a press or a drag, whoever takes it), so a follow of the playing source waits for the lift.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    onTouchActive(true)
                    try {
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Final)
                        } while (event.changes.any { it.pressed })
                    } finally {
                        onTouchActive(false)
                    }
                }
            },
        contentPadding = 0.dp,
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        // The heading line. Your music's Library chip sits at its right end (the browser's way in), so it costs the card no row of its own: the card is capped at the
        // display's height, and a row of 56 dp pushed the Music slider off it once the art was up.
        val openBrowser = browse.onOpen
        val chipOnHeading = model.browse == null && openBrowser != null && model.card(MusicPanelCard.YOURS)?.canBrowse == true && model.top.card == MusicPanelCard.YOURS
        // The heading text is what sizes the line; the chip is centred on it at its right end and may stand taller than the line (matchParentSize + unbounded), so it adds no
        // height of its own. The line starts lower when the chip is there, so the chip's ring has room above it inside the panel.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PaddingValues(start = 20.dp, end = 20.dp, top = if (chipOnHeading) MusicPanelChipHeadingTop else 16.dp, bottom = 4.dp)),
        ) {
            // The browser names its level where the card names itself: Artists, Albums, Tracks.
            MimirChromeText(
                model.browse?.level?.eyebrow ?: model.top.heading,
                style = MimirTypeScale.RunicEyebrow,
                color = palette.accent,
                modifier = Modifier.align(Alignment.CenterStart),
            )
            if (chipOnHeading) {
                Box(Modifier.matchParentSize().wrapContentSize(Alignment.CenterEnd, unbounded = true)) {
                    MusicPanelBrowseChip(
                        focused = focused == MusicPanelControl.BROWSE,
                        palette = palette,
                        modifier = control(MusicPanelControl.BROWSE, true),
                        pressResponse = false,
                        onActivate = openBrowser!!,
                    )
                }
            }
        }
        Box(
            Modifier
                .weight(1f, fill = false)
                .onGloballyPositioned { coordinates ->
                    viewportCoords[0] = coordinates
                    viewportHeightPx = coordinates.size.height
                    remeasureRows()
                },
        ) {
            val browser = model.browse
            if (browser != null) {
                MusicLibraryBrowserPage(
                    ui = browser,
                    palette = palette,
                    ringFocused = focused == MusicPanelControl.BROWSE_LIST,
                    onPress = browse.onPress,
                    onBack = browse.onBack,
                    modifier = Modifier
                        .padding(PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp))
                        .height(MusicBrowsePageMainDp.dp),
                    focusModifier = control(MusicPanelControl.BROWSE_LIST, true),
                    showEyebrow = false,
                )
            } else {
                Column(
                    modifier = Modifier
                        .verticalScroll(scroll)
                        .padding(
                            PaddingValues(
                                start = 20.dp,
                                end = 20.dp,
                                top = 8.dp,
                                // The panel clips at its bottom edge (no content padding): clear the ring and its flare, as Utility's footer does.
                                bottom = MimirFilledControlRingReach + MimirFocusFlareReach,
                            ),
                        ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    MusicPanelSources(
                        model = model,
                        focused = focused,
                        authority = authority,
                        palette = palette,
                        controlModifier = { which, reachable -> control(which, reachable) },
                        onPlayPause = onPlayPause,
                        onPrevious = onPrevious,
                        onNext = onNext,
                        onOpenOtherPlayer = onOpenOtherPlayer,
                        onSwitchSource = onSwitchSource,
                        onRequestFocus = onRequestFocus,
                        onArtPlaced = onArtPlaced,
                    )
                    MusicPanelLevels(
                        model = model,
                        focused = focused,
                        palette = palette,
                        controlModifier = { which, reachable -> control(which, reachable) },
                        onRequestFocus = onRequestFocus,
                        onMediaRequested = onMediaRequested,
                        onMusicRequested = onMusicRequested,
                        onMusicInteractionEnd = onMusicInteractionEnd,
                        onEqualizerStep = onEqualizerStep,
                    )
                }
            }
        }
    }
}

/**
 * The panel's levels, one under another: Game & media, Music, the cap-line seat, the Equalizer row and the foot hint. A pure extract of what
 * the card's column held, so the Bottom's panel seats the same rows in its own column (W2d) and Main's card draws exactly what it drew. It emits
 * its rows as siblings (no layout node of its own): the caller's column spaces them. [controlModifier] gives each row its focus registration
 * and layout reporting as the card's does; each is asked for only while its row is on the panel. [onMediaInteractionEnd] is the finger leaving
 * the Game & media track (the Bottom reports its one receipt there); Main leaves it as it was, a no-op.
 */
@Composable
internal fun MusicPanelLevels(
    model: MusicPanelModel,
    focused: MusicPanelControl?,
    palette: MimirThemePalette,
    controlModifier: @Composable (MusicPanelControl, Boolean) -> Modifier,
    onRequestFocus: (MusicPanelControl) -> Unit,
    onMediaRequested: (Float) -> Unit,
    onMusicRequested: (Float) -> Unit,
    onMusicInteractionEnd: () -> Unit,
    onEqualizerStep: (Int) -> Unit,
    onMediaInteractionEnd: () -> Unit = {},
    /** The Bottom's: the Equalizer row's step chevrons are touch-only activatables with the press response, not Compose focus targets. */
    pressResponse: Boolean = false,
) {
    MusicPanelMediaVolumeRow(
        model = model,
        focused = focused,
        palette = palette,
        controlModifier = controlModifier,
        onRequestFocus = onRequestFocus,
        onMediaRequested = onMediaRequested,
        onMediaInteractionEnd = onMediaInteractionEnd,
    )
    MusicPanelMusicVolumeRow(
        model = model,
        focused = focused,
        palette = palette,
        controlModifier = controlModifier,
        onRequestFocus = onRequestFocus,
        onMusicRequested = onMusicRequested,
        onMusicInteractionEnd = onMusicInteractionEnd,
    )
    MusicPanelCapLine(model = model, palette = palette)
    MusicPanelEqualizerRow(
        model = model,
        focused = focused,
        palette = palette,
        controlModifier = controlModifier,
        onRequestFocus = onRequestFocus,
        onEqualizerStep = onEqualizerStep,
        pressResponse = pressResponse,
    )
    MusicPanelAccessHint(model = model, palette = palette)
}

/*
 * The levels' pieces (W3c): MusicPanelLevels' own code, moved here unchanged so the Bottom's hub can seat them in a different arrangement (the two sliders
 * side by side, the Equalizer beside the switch row). Each draws nothing when the model does not show it.
 */

/** The Game & media slider row. */
@Composable
internal fun MusicPanelMediaVolumeRow(
    model: MusicPanelModel,
    focused: MusicPanelControl?,
    palette: MimirThemePalette,
    controlModifier: @Composable (MusicPanelControl, Boolean) -> Modifier,
    onRequestFocus: (MusicPanelControl) -> Unit,
    onMediaRequested: (Float) -> Unit,
    onMediaInteractionEnd: () -> Unit = {},
) {
    if (model.showMediaVolume) {
        UtilitySliderRow(
            label = MusicPanelMediaLabel,
            valueLabel = model.mediaReadout,
            fraction = model.mediaFraction,
            target = BrightnessTarget.MEDIA_VOLUME,
            enabled = !model.mediaFixed,
            focused = focused == MusicPanelControl.MEDIA_VOLUME,
            palette = palette,
            focusModifier = controlModifier(MusicPanelControl.MEDIA_VOLUME, !model.mediaFixed).testTag(MusicPanelMediaRowTestTag),
            onRequestFocus = { onRequestFocus(MusicPanelControl.MEDIA_VOLUME) },
            onValueChange = onMediaRequested,
            onInteractionEnd = onMediaInteractionEnd,
        )
    }
}

/** The Music slider row, with the cap marker. */
@Composable
internal fun MusicPanelMusicVolumeRow(
    model: MusicPanelModel,
    focused: MusicPanelControl?,
    palette: MimirThemePalette,
    controlModifier: @Composable (MusicPanelControl, Boolean) -> Modifier,
    onRequestFocus: (MusicPanelControl) -> Unit,
    onMusicRequested: (Float) -> Unit,
    onMusicInteractionEnd: () -> Unit,
) {
    if (model.showMusicVolume) {
        UtilitySliderRow(
            label = MusicPanelMusicLabel,
            valueLabel = model.musicReadout,
            fraction = model.musicFraction,
            target = BrightnessTarget.AMBIENT_SOUND,
            enabled = true,
            focused = focused == MusicPanelControl.MUSIC_VOLUME,
            palette = palette,
            focusModifier = controlModifier(MusicPanelControl.MUSIC_VOLUME, true).testTag(MusicPanelMusicRowTestTag),
            onRequestFocus = { onRequestFocus(MusicPanelControl.MUSIC_VOLUME) },
            onValueChange = onMusicRequested,
            markerFraction = model.capMarker,
            onInteractionEnd = onMusicInteractionEnd,
        )
    }
}

/** The cap-line seat under the sliders. */
@Composable
internal fun MusicPanelCapLine(model: MusicPanelModel, palette: MimirThemePalette) {
    if (model.capLineSeat) {
        // The seat is held while the cap is in play so the line appearing under a finger moves nothing.
        Box(Modifier.fillMaxWidth().height(16.dp), contentAlignment = Alignment.Center) {
            if (model.capLineVisible) {
                MimirChromeText(
                    MusicCapLine,
                    style = MimirTypeScale.ControlHintCompact,
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** The Equalizer preset row (the Bottom's step chevrons are touch-only activatables with the press response, not Compose focus targets). */
@Composable
internal fun MusicPanelEqualizerRow(
    model: MusicPanelModel,
    focused: MusicPanelControl?,
    palette: MimirThemePalette,
    controlModifier: @Composable (MusicPanelControl, Boolean) -> Modifier,
    onRequestFocus: (MusicPanelControl) -> Unit,
    onEqualizerStep: (Int) -> Unit,
    pressResponse: Boolean = false,
) {
    if (model.showEqualizer) {
        // The player's preset, one step at a time: left and right cycle it (it wraps, Custom last). Offered only while
        // the equalizer was on when the panel opened; the switch and the bands are in Settings › Music.
        UtilityChoiceRow(
            label = MusicPanelEqualizerLabel,
            supportingText = model.equalizerSupporting,
            valueLabel = model.equalizerLabel,
            stateDescription = model.equalizerLabel,
            focused = focused == MusicPanelControl.EQUALIZER,
            palette = palette,
            modifier = controlModifier(MusicPanelControl.EQUALIZER, true).testTag(MusicPanelEqualizerRowTestTag),
            onRequestFocus = { onRequestFocus(MusicPanelControl.EQUALIZER) },
            onStep = onEqualizerStep,
            canStepLeft = true,
            canStepRight = true,
            compactSupporting = true,
            pressResponse = pressResponse,
        )
    }
}

/** The foot hint under the levels (the panel is over a game: no button, Settings is not opened from over one). */
@Composable
internal fun MusicPanelAccessHint(model: MusicPanelModel, palette: MimirThemePalette) {
    model.accessHint?.let { hint ->
        // No button: the panel is over a game, and Settings is not opened from over one.
        MimirChromeText(
            hint,
            style = MimirTypeScale.ControlHintCompact,
            color = palette.textMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * How a card seats its art, its lines and its transport. The defaults are Main's panel as it is: the art over the lines, the lines centred, the transport at
 * the panel's own sizes. The Bottom's hub and its media tile ask for the stage (W3c, M2/M4): [artBeside] seats the art at the left with the status, artist and
 * hint beside it, and the transport takes the hub's or the tile's plate sizes and no focus-ring room (the Bottom is touch-only).
 */
internal data class MusicPanelCardLayout(
    val artBeside: Boolean = false,
    /** The art's side: Main's 96 dp; the hub's stage keeps it (the 112 dp override of W2d is retired). */
    val artSize: Dp = 96.dp,
    /** The space above and below the art: Main's 4 dp. */
    val artVerticalPadding: Dp = 4.dp,
    /** Between the art and its lines, when beside. */
    val artLinesGap: Dp = 12.dp,
    /** Between the lines of the stage, when beside. */
    val linesGap: Dp = 4.dp,
    /** The ring on a filled circle reaches past it: Main's transport row keeps that room above and below. */
    val transportVerticalPadding: Dp = MimirFocusTileRingReach - 6.dp,
    val transportGap: Dp = 14.dp,
    val skipTarget: Dp = MediaMainSkipTargetDp,
    val skipPlate: Dp = MediaHubSkipPlateDp,
    val play: Dp = MediaMainPlayDp,
)

/** The hub's stage: art 96 dp beside the lines, the transport at the hub's sizes (Play 56, skips 44 in 56 dp targets). */
internal val MusicPanelHubLayout = MusicPanelCardLayout(
    artBeside = true,
    artVerticalPadding = 0.dp,
    transportVerticalPadding = 0.dp,
    skipTarget = MediaHubSkipTargetDp,
    skipPlate = MediaHubSkipPlateDp,
    play = MediaHubPlayDp,
)

/**
 * One player's card in the flow: its art (another player's, as the companion card shows it), status line, artist, hint and
 * transport. [controlModifier] gives each button its focus registration and layout reporting. [layout] is Main's by default.
 */
@Composable
private fun MusicPanelCardBody(
    card: MusicPanelCardModel,
    focused: MusicPanelControl?,
    authority: FocusAuthority?,
    palette: MimirThemePalette,
    controlModifier: @Composable (MusicPanelControl, Boolean) -> Modifier,
    onPlayPause: (MusicPanelCard) -> Unit,
    onPrevious: (MusicPanelCard) -> Unit,
    onNext: (MusicPanelCard) -> Unit,
    onOpenOtherPlayer: () -> Unit,
    onArtPlaced: (View, Rect) -> Unit,
    layout: MusicPanelCardLayout = MusicPanelCardLayout(),
    /** The Bottom's one press response on the three transport buttons (B5); Main's stay as they were. */
    pressResponse: Boolean = false,
) {
    val previous = musicPanelTransportControl(card.card, MusicPanelTransport.PREVIOUS)
    val playPause = musicPanelTransportControl(card.card, MusicPanelTransport.PLAY_PAUSE)
    val next = musicPanelTransportControl(card.card, MusicPanelTransport.NEXT)
    val art: @Composable (MusicPanelCardModel) -> Unit = { shown ->
        MusicPanelArtwork(
            model = shown,
            palette = palette,
            focused = focused == MusicPanelControl.OTHER_ART,
            authority = authority,
            controlModifier = controlModifier(MusicPanelControl.OTHER_ART, card.canOpenPlayer),
            onOpen = onOpenOtherPlayer,
            onPlaced = onArtPlaced,
            // Main's Your music seat is the smaller one; every other card, and the Bottom's own layouts, keep the layout's size.
            artSize = if (shown.card == MusicPanelCard.YOURS && !layout.artBeside) MusicPanelYoursArtSizeDp.dp else layout.artSize,
            verticalPadding = layout.artVerticalPadding,
            // Every coverless book is set to its seat, Main's included: a word too wide for the leaf breaks at a hyphen or ends in an ellipsis, never cut inside (round 2).
            fitTitle = true,
        )
    }
    if (layout.artBeside && card.showArtwork) {
        // The stage: the art at the left, the lines beside it (left-aligned against the art).
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(layout.artLinesGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            art(card)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(layout.linesGap),
                horizontalAlignment = Alignment.Start,
            ) {
                MusicPanelCardLines(card, palette, TextAlign.Start)
            }
        }
    } else if (layout.artBeside) {
        // No art on this card (Your music): the lines take the stage, centred.
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(layout.linesGap),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MusicPanelCardLines(card, palette, TextAlign.Center)
        }
    } else {
        if (card.card == MusicPanelCard.YOURS) {
            // Your music has an art seat only for a track that carries a picture: it grows and shrinks in place on the card's clock.
            MusicPanelYoursArtSeat(card, art)
        } else if (card.showArtwork) {
            art(card)
        }
        MusicPanelCardLines(card, palette, TextAlign.Center)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = layout.transportVerticalPadding),
        horizontalArrangement = Arrangement.spacedBy(layout.transportGap, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaButton(
            label = "Previous",
            enabled = card.canPrevious,
            skipTargetSize = layout.skipTarget,
            skipPlateSize = layout.skipPlate,
            modifier = controlModifier(previous, card.canPrevious),
            focused = focused == previous,
            focusAuthority = authority,
            focusKey = musicPanelFocusKey(previous),
            pressResponse = pressResponse,
            onClick = { onPrevious(card.card) },
        )
        MediaButton(
            label = if (card.playing) "Pause" else "Play",
            enabled = card.canPlayPause,
            primary = true,
            primarySize = layout.play,
            modifier = controlModifier(playPause, card.canPlayPause),
            focused = focused == playPause,
            focusAuthority = authority,
            focusKey = musicPanelFocusKey(playPause),
            pressResponse = pressResponse,
            onClick = { onPlayPause(card.card) },
        )
        MediaButton(
            label = "Next",
            enabled = card.canNext,
            skipTargetSize = layout.skipTarget,
            skipPlateSize = layout.skipPlate,
            modifier = controlModifier(next, card.canNext),
            focused = focused == next,
            focusAuthority = authority,
            focusKey = musicPanelFocusKey(next),
            pressResponse = pressResponse,
            onClick = { onNext(card.card) },
        )
    }
}

/**
 * The card's three lines: the status, the artist and the hint. The lines change in place (a new track, a format arriving, a pause hint): each is the
 * companion's own text swap, a crossfade on the overlay clock with the size following, so nothing pops in or out.
 * Keyed on the line AND whether its title is authored, so a crossfade between a real title and the fallback draws each in its own treatment.
 */
@Composable
private fun MusicPanelCardLines(card: MusicPanelCardModel, palette: MimirThemePalette, textAlign: TextAlign) {
    MusicPanelSwapLine(card.statusLine to card.titleIsAuthored) { (shown, authored) ->
        MusicPanelStatusLine(shown, titleIsAuthored = authored, playing = card.playing, palette = palette, textAlign = textAlign)
    }
    MusicPanelSwapLine(card.artist?.let { it to card.artistIsAuthored }) { (artist, authored) ->
        MusicPanelArtistLine(artist, authored = authored, palette = palette, textAlign = textAlign)
    }
    MusicPanelSwapLine(card.hint) { hint ->
        MimirChromeText(
            hint,
            style = MimirTypeScale.ControlHintCompact,
            color = palette.textMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
            textAlign = textAlign,
        )
    }
}

/**
 * Your music's art seat: composed only while the track carries a picture, so a track without one has exactly the card it always had (no seat, no gap). The seat
 * opens to its height on the card's clock, the art revealed as it opens, so a card that gains art does not jump under the player's eyes. Reduced motion opens it
 * at once.
 */
@Composable
private fun MusicPanelYoursArtSeat(card: MusicPanelCardModel, art: @Composable (MusicPanelCardModel) -> Unit) {
    if (!card.showArtwork) return
    val reduced = mimirReducedMotion()
    val open = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (open.value < 1f) open.animateTo(1f, tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing))
    }
    Layout(content = { art(card) }, modifier = Modifier.clipToBounds()) { measurables, constraints ->
        val placeable = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
        // Read in the layout phase: the opening re-lays the card out each frame and recomposes nothing.
        val height = (placeable.height * open.value).roundToInt()
        layout(placeable.width, height) { placeable.place(0, 0) }
    }
}

/**
 * The other player's artwork. Tapping it (or A on its ring) opens that player's app, growing out of this very rectangle: the
 * art reports its bounds in the window each time it is laid out ([onPlaced]). Your music's card has no art, so it has no such control.
 * [controlModifier] is the focus registration; a card that can no longer be opened (the panel is showing "Stopped") draws the art
 * and takes no focus and no tap.
 */
@Composable
internal fun MusicPanelArtwork(
    model: MusicPanelCardModel,
    palette: MimirThemePalette,
    focused: Boolean,
    authority: FocusAuthority?,
    controlModifier: Modifier,
    onOpen: () -> Unit,
    onPlaced: (View, Rect) -> Unit,
    artSize: Dp = 96.dp,
    /** The space above and below the art: Main's 4 dp; the Bottom's media tile seats it with none. */
    verticalPadding: Dp = 4.dp,
    /**
     * Sets the coverless book's title to the book (the ruled fit: a word too wide for the leaf breaks at a syllable hyphen, else the title is cut to the room ending in an ellipsis, and no word is
     * cut where the leaf runs out). Off by default; every caller turns it on now (Main's panel since round 2 of the 10-11 sweep, the Bottom's tile and player before it).
     */
    fitTitle: Boolean = false,
) {
    val bitmap = model.artwork
    val image = remember(bitmap) { bitmap?.takeIf { !it.isRecycled }?.asImageBitmap() }
    // Artwork continuity: art that is wired is never replaced by the fallback, not even for a frame. While the load is
    // pending the art area is left empty; the fallback is for a load that finished with no art; art that arrives fades in on the house clock.
    // A load that FAILED (R8, 2026-10-02) takes the same path as a pending one: wired art that is gone draws neither plate nor glyph.
    val pending = (model.artworkPending || model.artworkFailed) && image == null
    val artAlpha by animateFloatAsState(
        targetValue = if (image != null) 1f else 0f,
        animationSpec = tween(if (mimirReducedMotion()) 0 else MimirMotion.OverlayMs, easing = MimirMotion.Easing),
        label = "music-panel-art",
    )
    val view = LocalView.current
    val key = musicPanelFocusKey(MusicPanelControl.OTHER_ART)
    Box(
        modifier = controlModifier
            .padding(vertical = verticalPadding)
            .size(artSize)
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInRoot()
                onPlaced(view, Rect(bounds.left.roundToInt(), bounds.top.roundToInt(), bounds.right.roundToInt(), bounds.bottom.roundToInt()))
            }
            .mimirSelectionRing(focused = focused, identity = key.value, shape = MimirRingShape.Row(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .then(if (image == null && !pending) Modifier.background(palette.surfaceHigh) else Modifier)
            .then(
                if (model.canOpenPlayer) {
                    Modifier.mimirActivatable(
                        label = MusicPanelOpenPlayerLabel,
                        authority = authority,
                        key = key,
                        onActivate = onOpen,
                    )
                } else {
                    Modifier.clearAndSetSemantics { }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().testTag(MusicPanelArtImageTestTag).graphicsLayer { alpha = artAlpha },
            )
        } else if (!pending) {
            Box(Modifier.fillMaxSize().testTag(MusicPanelArtFallbackTestTag), contentAlignment = Alignment.Center) {
                MediaArtFallbackGlyph(model.artworkTitle, palette = palette, showTitle = false, fitTitle = fitTitle)
            }
        }
    }
}

/**
 * One line of a card that changes in place. The companion's own swap (the game-display chooser and the instruments surface): fade in
 * and out together on [MimirMotion.OverlayMs] and the house easing, the size following on the same clock so a line arriving or leaving
 * (the format line once the player has reported it, a pause hint) never pops the card. A null [text] is no line. Reduced motion jumps.
 */
@Composable
private fun <T : Any> MusicPanelSwapLine(text: T?, content: @Composable (T) -> Unit) {
    val reduced = mimirReducedMotion()
    val ms = if (reduced) 0 else MimirMotion.OverlayMs
    AnimatedContent(
        targetState = text,
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
        transitionSpec = {
            (fadeIn(tween(ms, easing = MimirMotion.Easing)) togetherWith fadeOut(tween(ms, easing = MimirMotion.Easing))).using(
                SizeTransform(clip = false) { _, _ -> tween(ms, easing = MimirMotion.Easing) },
            )
        },
        label = "music-panel-line",
    ) { shown ->
        if (shown != null) content(shown)
    }
}

/**
 * The full card and, under it, the switch row. When the card's source changes (the row pressed, either player starting or stopping, the
 * last-played fallback) the card takes the house swap tier, the one every in-place page change uses: the old card fades out on
 * [MimirMotion.OverlayMs], the new one fades in after the measured [MimirMotion.OverlayDissolveCoverMs] lead (no frame has both above
 * alpha 0.15) and rises [MimirMotion.SwapTravelDp] into place, and the container's height follows on the arrival's clock, so the panel
 * never jumps. The card fades through [mimirRingSafeFade] (no offscreen layer), so a button's ring is never cut mid-fade; the outgoing
 * layer registers no focus and takes no touch, and the arriving one takes none until it shows.
 *
 * The switch row is NOT inside that swap: it is one persistent node, so the ring stays on it through the change with no flash. Only its
 * text swaps, on the same clocks. Its presence (a second source arriving or leaving) grows and shrinks it on [MimirMotion.OverlayMs].
 */
@Composable
internal fun MusicPanelSources(
    model: MusicPanelModel,
    focused: MusicPanelControl?,
    authority: FocusAuthority?,
    palette: MimirThemePalette,
    controlModifier: @Composable (MusicPanelControl, Boolean) -> Modifier,
    onPlayPause: (MusicPanelCard) -> Unit,
    onPrevious: (MusicPanelCard) -> Unit,
    onNext: (MusicPanelCard) -> Unit,
    onOpenOtherPlayer: () -> Unit,
    onSwitchSource: () -> Unit,
    onRequestFocus: (MusicPanelControl) -> Unit,
    onArtPlaced: (View, Rect) -> Unit,
    /** How the card seats its art, lines and transport: Main's by default; the Bottom's hub passes [MusicPanelHubLayout] (W3c). */
    layout: MusicPanelCardLayout = MusicPanelCardLayout(),
    /** The Bottom's one press response (B5) on the transport and the switch row; Main's draw as they always did. */
    pressResponse: Boolean = false,
    /**
     * Whether the card swap holds the process-wide pad gate while it arrives. Main's panel does; the Bottom is touch-only and passes false, so a switch
     * press there can never silence Main's controller for the lead.
     */
    holdPad: Boolean = true,
    /** Whether the switch row is seated under the card here (Main's panel). The hub seats it in its own row beside the Equalizer, through [MusicPanelSwitchRowSlot]. */
    includeSwitchRow: Boolean = true,
) {
    val reduced = mimirReducedMotion()
    val travelPx = with(LocalDensity.current) { MimirMotion.SwapTravelDp.dp.roundToPx() }
    val shown = model.cards.first()
    // What each layer showed while it was live, so the one that is leaving dissolves exactly as it was. Plain memory, written only here.
    val cardMemory = remember { mutableMapOf<MusicPanelCard, MusicPanelCardModel>() }
    cardMemory[shown.card] = shown
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedContent(
            targetState = shown.card,
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.TopCenter,
            transitionSpec = { mimirSwapTransform(reduced, fade = false, enterTravelPx = travelPx) },
            label = "music-panel-card-swap",
        ) { presented ->
            val live = presented == shown.card
            val body = if (live) shown else cardMemory[presented] ?: shown
            val alpha = mimirSwapFadeAlpha(reduced)
            val pageInput = MimirSwapPageGate(alpha, holdPad = holdPad)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // The tag first: the gate below clears the semantics of what follows it on this node.
                    .testTag(MusicPanelCardLayerTestTag + presented.name)
                    .mimirRingSafeFade(alpha)
                    .then(pageInput),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                MusicPanelCardBody(
                    card = body,
                    focused = if (live) focused else null,
                    authority = authority,
                    palette = palette,
                    controlModifier = { which, reachable -> controlModifier(which, reachable && live) },
                    onPlayPause = onPlayPause,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onOpenOtherPlayer = onOpenOtherPlayer,
                    onArtPlaced = onArtPlaced,
                    layout = layout,
                    pressResponse = pressResponse,
                )
            }
        }
        if (includeSwitchRow) {
            MusicPanelSwitchRowSlot(
                model = model,
                focused = focused,
                palette = palette,
                controlModifier = controlModifier,
                onRequestFocus = onRequestFocus,
                onSwitchSource = onSwitchSource,
                pressResponse = pressResponse,
            )
        }
    }
}

/**
 * The switch row's seat: the row itself and its presence (a second source arriving or leaving grows and shrinks it on [MimirMotion.OverlayMs]). Moved out of
 * [MusicPanelSources] unchanged so the Bottom's hub can seat it beside the Equalizer; Main's panel still draws it under the card, through the same code.
 * [topPadding] is the room above the row: Main's 8 dp; the hub spaces its own rows and passes zero.
 */
@Composable
internal fun MusicPanelSwitchRowSlot(
    model: MusicPanelModel,
    focused: MusicPanelControl?,
    palette: MimirThemePalette,
    controlModifier: @Composable (MusicPanelControl, Boolean) -> Modifier,
    onRequestFocus: (MusicPanelControl) -> Unit,
    onSwitchSource: () -> Unit,
    pressResponse: Boolean = false,
    topPadding: Dp = 8.dp,
) {
    val reduced = mimirReducedMotion()
    val rowMemory = remember { arrayOfNulls<MusicPanelSwitchRow>(1) }
    model.switchRow?.let { rowMemory[0] = it }
    val rowSpec = tween<Float>(if (reduced) 0 else MimirMotion.OverlayMs, easing = MimirMotion.Easing)
    val sizeSpec = tween<androidx.compose.ui.unit.IntSize>(if (reduced) 0 else MimirMotion.OverlayMs, easing = MimirMotion.Easing)
    AnimatedVisibility(
        visible = model.switchRow != null,
        enter = expandVertically(sizeSpec, clip = false),
        exit = shrinkVertically(sizeSpec, clip = false),
    ) {
        val row = model.switchRow ?: rowMemory[0]
        if (row != null) {
            val presence = mimirRingSafeFadeAlpha(enter = rowSpec, exit = rowSpec, label = "music-panel-switch-presence")
            Box(Modifier.fillMaxWidth().padding(top = topPadding).mimirRingSafeFade(presence)) {
                MusicPanelSwitchRowItem(
                    row = row,
                    live = model.switchRow != null,
                    focused = focused == MusicPanelControl.SWITCH,
                    palette = palette,
                    modifier = controlModifier(MusicPanelControl.SWITCH, model.switchRow != null),
                    onRequestFocus = { onRequestFocus(MusicPanelControl.SWITCH) },
                    onActivate = onSwitchSource,
                    pressResponse = pressResponse,
                )
            }
        }
    }
}

/** A layer of the card swap, for the test harness: the card's tag plus the source it shows. */
internal const val MusicPanelCardLayerTestTag = "music/panel/card-layer/"

/** The art area's two drawn states, for the test harness: the art itself, and the fallback glyph (only for a load that finished with no art). */
internal const val MusicPanelArtImageTestTag = "music/panel/art/image"
internal const val MusicPanelArtFallbackTestTag = "music/panel/art/fallback"

/** The switch row's tag, for the test harness. */
internal const val MusicPanelSwitchRowTestTag = "music/panel/switch-row"

/** Above the heading line when the Library chip sits on it: the chip's plate is taller than the heading's text and stands out of the line on both sides, so the line starts lower and the chip's ring has room above it inside the panel. */
internal val MusicPanelChipHeadingTop = 22.dp

/** The level rows' tags, for the test harness: the card must show the Music slider (and the rows under it) whole, whatever the art and the Library chip add above. */
internal const val MusicPanelMediaRowTestTag = "music/panel/row/media"
internal const val MusicPanelMusicRowTestTag = "music/panel/row/music"
internal const val MusicPanelEqualizerRowTestTag = "music/panel/row/equalizer"

/**
 * The compact switch row: the other source's name over its track, "Play" at the end. The row (its ring, its focus node, its press)
 * is one persistent node, so a swap of the source it names never moves the ring; only the name and track swap, inside it, on the
 * card's own clocks. The ring's identity is the control's key, not the name, so it does not re-arrive when the name changes.
 */
@Composable
private fun MusicPanelSwitchRowItem(
    row: MusicPanelSwitchRow,
    live: Boolean,
    focused: Boolean,
    palette: MimirThemePalette,
    modifier: Modifier,
    onRequestFocus: () -> Unit,
    onActivate: () -> Unit,
    pressResponse: Boolean = false,
) {
    val reduced = mimirReducedMotion()
    val travelPx = with(LocalDensity.current) { MimirMotion.SwapTravelDp.dp.roundToPx() }
    val memory = remember { mutableMapOf<MusicPanelCard, MusicPanelSwitchRow>() }
    memory[row.card] = row
    val ringKey = musicPanelFocusKey(MusicPanelControl.SWITCH)
    val pressSource = remember { MutableInteractionSource() }
    val activation = if (live) {
        Modifier.mimirActivatable(
            label = row.name,
            role = null,
            interactionSource = if (pressResponse) pressSource else null,
        ) {
            onRequestFocus()
            onActivate()
        }
    } else {
        Modifier.clearAndSetSemantics { }
    }
    val seat = modifier
        .fillMaxWidth()
        .heightIn(min = 48.dp)
        .testTag(MusicPanelSwitchRowTestTag)
    // Main's chain is what it always was: the ring, the clip, then the press. The Bottom's (B5) takes the press first and the response right after it, so the
    // whole row draws in and its hit box stays whole; the ring and the clip come after.
    val chain = if (pressResponse) {
        seat.then(activation).then(Modifier.mimirPressResponse(pressSource))
            .mimirSelectionRing(focused = focused && live, identity = ringKey.value, shape = MimirRingShape.Row(UtilityRowCorner))
            .clip(RoundedCornerShape(UtilityRowCorner))
    } else {
        seat
            .mimirSelectionRing(focused = focused && live, identity = ringKey.value, shape = MimirRingShape.Row(UtilityRowCorner))
            .clip(RoundedCornerShape(UtilityRowCorner))
            .then(activation)
    }
    Row(
        modifier = chain.padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AnimatedContent(
            targetState = row.card,
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart,
            transitionSpec = { mimirSwapTransform(reduced, fade = false, enterTravelPx = travelPx) },
            label = "music-panel-switch-text",
        ) { presented ->
            val text = if (presented == row.card) row else memory[presented] ?: row
            val alpha = mimirSwapFadeAlpha(reduced)
            Column(Modifier.mimirRingSafeFade(alpha)) {
                MimirChromeText(text.name, style = MimirTypeScale.MenuAction, color = palette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                MusicPanelSwitchTrackLine(text, palette)
            }
        }
        MimirChromeText(
            MusicPanelSwitchPlay,
            style = MimirTypeScale.MicroStatus,
            color = if (focused) palette.accentBright else palette.textSecondary,
            maxLines = 1,
        )
    }
}

/**
 * The card's status line: "Playing — {title}". The state word is chrome and is capped; the track title after the dash is authored and keeps its own case
 * (Thor Bottom revamp, ruling 7). A title the bridge synthesised ("Media unavailable") is not authored: the whole line is chrome, capped as it always was.
 */
@Composable
internal fun MusicPanelStatusLine(line: String, titleIsAuthored: Boolean, playing: Boolean, palette: MimirThemePalette, textAlign: TextAlign = TextAlign.Center) {
    MimirIntentText(
        musicPanelStatusIntent(line, titleIsAuthored),
        style = MimirTypeScale.MenuAction.copy(fontSize = (MimirTypeScale.MenuAction.fontSize.value + 2f).sp),
        color = if (playing) palette.accentBright else palette.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
        textAlign = textAlign,
    )
}

/**
 * The line under the status. Another player's artist is authored (its own case, ruling 7); Your music's line is the track's source format
 * ("FLAC · 24/96 · 2,304 kbps"), which is chrome and stays capped.
 */
@Composable
internal fun MusicPanelArtistLine(artist: String, authored: Boolean, palette: MimirThemePalette, textAlign: TextAlign = TextAlign.Center) {
    MimirIntentText(
        if (authored) MimirTextIntent.Authored(artist) else MimirTextIntent.StaticChrome(artist),
        style = MimirTypeScale.ControlHintCompact,
        color = palette.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
        textAlign = textAlign,
    )
}

/** The switch row's second line: the other source's track title (authored, own case) or, when it names none or the bridge synthesised one, its state word (chrome, capped). */
@Composable
internal fun MusicPanelSwitchTrackLine(row: MusicPanelSwitchRow, palette: MimirThemePalette) {
    MimirIntentText(
        if (row.trackIsTitle) MimirTextIntent.Authored(row.track) else MimirTextIntent.StaticChrome(row.track),
        style = MimirTypeScale.SupportCompact,
        color = palette.textMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
